package com.github.kevindagame.voxelsniperforge;

import com.github.kevindagame.VoxelBrushManager;
import com.github.kevindagame.VoxelSniper;
import com.github.kevindagame.command.VoxelCommandManager;
import com.github.kevindagame.util.Messages;
import com.github.kevindagame.util.schematic.DataFolderSchematicReader;
import com.github.kevindagame.voxelsniper.Environment;
import com.github.kevindagame.voxelsniper.IVoxelsniper;
import com.github.kevindagame.voxelsniper.biome.VoxelBiome;
import com.github.kevindagame.voxelsniper.entity.entitytype.VoxelEntityType;
import com.github.kevindagame.voxelsniper.entity.player.IPlayer;
import com.github.kevindagame.voxelsniper.fileHandler.IFileHandler;
import com.github.kevindagame.voxelsniper.fileHandler.VoxelSniperConfiguration;
import com.github.kevindagame.voxelsniper.material.VoxelMaterial;
import com.github.kevindagame.voxelsniper.treeType.VoxelTreeType;
import com.github.kevindagame.voxelsniperforge.entity.player.ForgePlayer;
import com.github.kevindagame.voxelsniperforge.fileHandler.ForgeFileHandler;
import com.github.kevindagame.voxelsniperforge.material.BlockMaterial;
import com.github.kevindagame.voxelsniperforge.material.ItemMaterial;
import com.github.kevindagame.voxelsniperforge.permissions.ForgePermissionManager;
import com.github.kevindagame.voxelsniperforge.world.ForgeWorld;

import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.feature.ChorusPlantFeature;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.feature.HugeFungusConfiguration;
import net.minecraft.world.level.levelgen.feature.configurations.HugeMushroomFeatureConfiguration;
import net.minecraft.world.level.levelgen.feature.configurations.TreeConfiguration;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import net.neoforged.neoforge.server.permission.events.PermissionGatherEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

// The value here should match an entry in the META-INF/neoforge.mods.toml file
@Mod(VoxelSniperForge.MODID)
public class VoxelSniperForge implements IVoxelsniper {

    // Define mod id in a common place for everything to reference
    public static final String MODID = "voxelsniper";
    private final Logger LOGGER;
    private ForgeFileHandler fileHandler;
    private VoxelSniperConfiguration voxelSniperConfiguration;
    private static VoxelSniperForge instance;

    private final Map<UUID, ForgePlayer> players = new HashMap<>();
    private final Map<ResourceKey<net.minecraft.world.level.Level>, ForgeWorld> worlds = new HashMap<>();
    private final ForgeVoxelSniperListener listener;

    public static VoxelSniperForge getInstance() {
        return instance;
    }

    public VoxelSniperForge(IEventBus modEventBus) {
        LOGGER = Logger.getLogger(MODID);
        VoxelSniper.voxelsniper = this;
        VoxelSniperForge.instance = this;

        VoxelBrushManager brushManager = VoxelBrushManager.initialize();
        getLogger().log(Level.INFO, "Registered {0} Sniper Brushes with {1} handles.", new Object[]{brushManager.registeredSniperBrushes(), brushManager.registeredSniperBrushHandles()});

        // Register ourselves for server and other game events we are interested in
        this.listener = new ForgeVoxelSniperListener(this);
        NeoForge.EVENT_BUS.register(this);
        NeoForge.EVENT_BUS.register(this.listener);
    }

    public ForgeVoxelSniperListener getListener() {
        return listener;
    }

    @SubscribeEvent
    public final void registerCommands(final RegisterCommandsEvent event) {
        ForgeCommandManager.initialize(event.getDispatcher());
        // On the first start the brush argument maps are added in onServerStarting, once the registries exist.
        // /reload builds a new dispatcher and command manager while the server runs, so add them right away.
        if (this.voxelSniperConfiguration != null && ServerLifecycleHooks.getCurrentServer() != null) {
            VoxelCommandManager.getInstance().registerBrushSubcommands();
        }
    }

    @SubscribeEvent
    public final void registerPermissionNodes(final PermissionGatherEvent.Nodes event) {
        ForgePermissionManager.register(event);
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        this.fileHandler = new ForgeFileHandler(this);
        DataFolderSchematicReader.Companion.initialize();
        Messages.load(this);

        voxelSniperConfiguration = new VoxelSniperConfiguration(this);
        VoxelCommandManager.getInstance().registerBrushSubcommands();
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        worlds.clear();
        players.clear();
    }

    @SubscribeEvent
    public void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) {
            worlds.remove(level.dimension());
        }
    }

    @SubscribeEvent
    public void onPlayerLeave(PlayerEvent.PlayerLoggedOutEvent event) {
        players.remove(event.getEntity().getUUID());
    }

    private static Registry<Biome> biomeRegistry() {
        return ServerLifecycleHooks.getCurrentServer().registryAccess().registryOrThrow(Registries.BIOME);
    }

    private static Registry<ConfiguredFeature<?, ?>> featureRegistry() {
        return ServerLifecycleHooks.getCurrentServer().registryAccess().registryOrThrow(Registries.CONFIGURED_FEATURE);
    }

    @Nullable
    @Override
    public IPlayer getPlayer(UUID uuid) {
        return getPlayer(ServerLifecycleHooks.getCurrentServer().getPlayerList().getPlayer(uuid));
    }

    @Nullable
    @Override
    public IPlayer getPlayer(String name) {
        return getPlayer(ServerLifecycleHooks.getCurrentServer().getPlayerList().getPlayerByName(name));
    }

    @Nullable
    public IPlayer getPlayer(@Nullable ServerPlayer p) {
        if (p == null) return null;
        ForgePlayer cached = this.players.get(p.getUUID());
        if (cached != null) {
            // Respawning and leaving the End replace the ServerPlayer; keep the Sniper, follow the new entity.
            if (cached.getPlayer() != p) cached.rebind(p);
            return cached;
        }
        ForgePlayer res = new ForgePlayer(p);
        this.players.put(res.getUniqueId(), res);
        return res;
    }

    @Override
    public Environment getEnvironment() {
        return Environment.FORGE;
    }

    @Override
    public VoxelSniperConfiguration getVoxelSniperConfiguration() {
        return this.voxelSniperConfiguration;
    }

    @Override
    public IFileHandler getFileHandler() {
        return this.fileHandler;
    }

    @Override
    public Logger getLogger() {
        return LOGGER;
    }

    @Override
    public List<String> getOnlinePlayerNames() {
        //get online player names
        return Arrays.stream(ServerLifecycleHooks.getCurrentServer().getPlayerNames()).toList();
    }

    @Nullable
    @Override
    public VoxelMaterial getMaterial(String namespace, String key) {
        var resourceLocation = ResourceLocation.fromNamespaceAndPath(namespace, key);
        if (BuiltInRegistries.BLOCK.containsKey(resourceLocation)) {
            var block = BuiltInRegistries.BLOCK.get(resourceLocation);
            return new BlockMaterial(block, namespace, key);
        }
        if (BuiltInRegistries.ITEM.containsKey(resourceLocation)) {
            var item = BuiltInRegistries.ITEM.get(resourceLocation);
            return new ItemMaterial(item, namespace, key);
        }
        throw new IllegalArgumentException("Requested material does not exist");
    }

    @Override
    public List<VoxelMaterial> getMaterials() {
        return BuiltInRegistries.BLOCK.stream().map(block -> {
            var key = BuiltInRegistries.BLOCK.getKey(block);
            return new BlockMaterial(block, key.getNamespace(), key.getPath());
        }).collect(Collectors.toList());
    }

    @Nullable
    @Override
    public VoxelBiome getBiome(String namespace, String key) {
        return biomeRegistry().get(ResourceLocation.fromNamespaceAndPath(namespace, key)) != null ? new VoxelBiome(namespace, key) : null;
    }

    @Override
    public List<VoxelBiome> getBiomes() {
        return biomeRegistry().keySet().stream().map(resourceLocation -> new VoxelBiome(resourceLocation.getNamespace(), resourceLocation.getPath())).collect(Collectors.toList());
    }

    @Nullable
    @Override
    public VoxelEntityType getEntityType(String namespace, String key) {
        var entityType = BuiltInRegistries.ENTITY_TYPE.get(ResourceLocation.fromNamespaceAndPath(namespace, key));
        return entityType != null ? new VoxelEntityType(namespace, key) : null;
    }

    @Override
    public List<VoxelEntityType> getEntityTypes() {
        return BuiltInRegistries.ENTITY_TYPE.stream().map(entityType -> {
            var key = BuiltInRegistries.ENTITY_TYPE.getKey(entityType);
            return new VoxelEntityType(key.getNamespace(), key.getPath());
        }).collect(Collectors.toList());
    }

    @Nullable
    @Override
    public VoxelTreeType getTreeType(String namespace, String key) {
        return featureRegistry().get(ResourceLocation.fromNamespaceAndPath(namespace, key)) != null ? new VoxelTreeType(namespace, key) : null;
    }

    @Override
    @NotNull
    public VoxelTreeType getDefaultTreeType() {
        return new VoxelTreeType("minecraft", "oak");
    }

    @Override
    public List<VoxelTreeType> getTreeTypes() {
        return featureRegistry().entrySet().stream().filter(e -> isTreeType(e.getValue())).map(e -> {
            var loc = e.getKey().location();
            return new VoxelTreeType(loc.getNamespace(), loc.getPath());
        }).collect(Collectors.toList());
    }

    public static boolean isTreeType(@NotNull ConfiguredFeature<?, ?> feature) {
        return feature.config() instanceof TreeConfiguration ||
                feature.config() instanceof HugeMushroomFeatureConfiguration ||
                feature.config() instanceof HugeFungusConfiguration ||
                feature.feature() instanceof ChorusPlantFeature;
    }

    @NotNull
    public ForgeWorld getWorld(@NotNull ServerLevel level) {
        // Keyed by dimension: ServerLevel.toString() is "ServerLevel[<save name>]" for every dimension.
        var cached = this.worlds.get(level.dimension());
        if (cached != null && cached.level() == level) return cached;
        ForgeWorld res = new ForgeWorld(level);
        this.worlds.put(level.dimension(), res);
        return res;
    }
}
