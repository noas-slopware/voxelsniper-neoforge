package com.github.kevindagame.voxelsniperforge.entity.player;

import com.github.kevindagame.snipe.Sniper;
import com.github.kevindagame.voxelsniper.entity.IEntity;
import com.github.kevindagame.voxelsniper.entity.entitytype.VoxelEntityType;
import com.github.kevindagame.voxelsniper.entity.player.IPlayer;
import com.github.kevindagame.voxelsniper.location.BaseLocation;
import com.github.kevindagame.voxelsniper.material.VoxelMaterial;
import com.github.kevindagame.voxelsniper.vector.VoxelVector;
import com.github.kevindagame.voxelsniperforge.entity.ForgeEntity;
import com.github.kevindagame.voxelsniperforge.location.ForgeLocation;
import com.github.kevindagame.voxelsniperforge.permissions.ForgePermissionManager;
import com.google.common.base.Preconditions;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.ComponentLike;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.server.permission.PermissionAPI;
import com.github.kevindagame.voxelsniperforge.util.ComponentConverter;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;
import java.util.UUID;

public class ForgePlayer extends ForgeEntity implements IPlayer {
    private ServerPlayer player;
    private final @NotNull Sniper sniper;

    public ForgePlayer(ServerPlayer player) {
        super(player);
        this.player = player;
        this.sniper = new Sniper(this);
    }

    /** Points this player at a new ServerPlayer of the same profile (after a respawn), keeping the Sniper. */
    public void rebind(ServerPlayer player) {
        this.player = player;
        setEntity(player);
    }

    @Override
    public UUID getUniqueId() {
        return player.getUUID();
    }

    @Override
    public void sendMessage(String message) {
        this.player.sendSystemMessage(net.minecraft.network.chat.Component.literal(message));
    }

    @Override
    public void sendMessage(@NotNull Component message) {
        this.player.sendSystemMessage(toNative(message));
    }

    @Override
    public void sendMessage(@NotNull ComponentLike message) {
        IPlayer.super.sendMessage(message);
    }

    public static MutableComponent toNative(@NotNull Component component) {
        return ComponentConverter.toNative(component);
    }

    @Override
    public boolean hasPermission(String permissionNode) {
        return PermissionAPI.getPermission(this.player, ForgePermissionManager.getPermissionNode(permissionNode));
    }

    @Override
    public boolean isSneaking() {
        return player.isCrouching();
    }

    @Override
    public String getName() {
        return player.getName().getString();
    }

    @Override
    public void teleport(BaseLocation location) {
        var world = location.getWorld();
        ServerLevel target = world instanceof com.github.kevindagame.voxelsniperforge.world.ForgeWorld forgeWorld ? forgeWorld.getLevel() : player.serverLevel();
        player.teleportTo(target, location.getX(), location.getY(), location.getZ(), location.getYaw(), location.getPitch());
    }

    @Override
    public void remove() {

    }

    @Override
    public boolean teleport(IEntity other) {
        var location = other.getLocation();
        Preconditions.checkArgument(location.getWorld() != null, "location.world");

        if (this.player.getHealth() == 0 || this.player.isRemoved()) {
            return false;
        }

        if (this.player.isVehicle()) {
            return false;
        }

        // If this player is riding another entity, we must dismount before teleporting.
        this.player.stopRiding();

        if (this.player.isSleeping()) {
            this.player.stopSleepInBed(true, false);
        }

        // Close any foreign inventory
        if (this.player.containerMenu != this.player.inventoryMenu) {
            this.player.closeContainer();
        }

        ServerLevel toWorld = (ServerLevel) ((ForgeEntity) other).getEntity().level();
        this.player.teleportTo(toWorld, location.getX(), location.getY(), location.getZ(), location.getYaw(), location.getPitch());
        return true;
    }

    @Override
    public IEntity launchProjectile(VoxelEntityType type, VoxelVector velocity) {
        var tag = EntityType.byString(type.getKey());
        if (tag.isPresent()){
            Entity created = tag.get().create(player.level());
            if (created instanceof Projectile) {
                created.setPos(player.getX(), player.getY(), player.getZ());
                Vec3 vector = new Vec3(velocity.getX(), velocity.getY(), velocity.getZ());
                created.setDeltaMovement(vector);
                player.level().addFreshEntity(created);
            }
        } else {
            throw new IllegalArgumentException("Invalid entity type");
        }
        return null;
    }

    @Override
    public VoxelMaterial getItemInHand() {
        var itemStack = player.getMainHandItem();
        ResourceLocation item = BuiltInRegistries.ITEM.getKey(itemStack.getItem());
        return VoxelMaterial.getMaterial(item.getNamespace(), item.getPath());
    }

    @NotNull
    @Override
    public Sniper getSniper() {
        return this.sniper;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ForgePlayer that = (ForgePlayer) o;
        return player.getUUID().equals(that.player.getUUID());
    }

    @Override
    public int hashCode() {
        return Objects.hash(player.getUUID());
    }

    public ServerPlayer getPlayer() {
        return player;
    }
}
