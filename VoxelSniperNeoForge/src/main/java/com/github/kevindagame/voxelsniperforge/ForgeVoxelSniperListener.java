package com.github.kevindagame.voxelsniperforge;

import com.github.kevindagame.snipe.Sniper;
import com.github.kevindagame.voxelsniper.block.BlockFace;
import com.github.kevindagame.voxelsniper.block.IBlock;
import com.github.kevindagame.voxelsniper.entity.player.IPlayer;
import com.github.kevindagame.voxelsniper.material.VoxelMaterial;
import com.github.kevindagame.voxelsniperforge.location.ForgeLocation;
import com.github.kevindagame.voxelsniperforge.material.ItemMaterial;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.ICancellableEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.item.ItemTossEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Turns clicks into snipes.
 * <p>
 * The mod is server side only, so it can only use what reaches the server: right clicks on blocks and in the air
 * ({@code RightClickBlock}, {@code RightClickItem}), left clicks on blocks in reach ({@code LeftClickBlock}), and arm
 * swings, which a {@code ServerGamePacketListenerImpl} mixin turns into left clicks in the air (like CraftBukkit does).
 * NeoForge's {@code LeftClickEmpty}/{@code RightClickEmpty} only fire on the client.
 *
 * @author KevinDaGame
 */
public class ForgeVoxelSniperListener {

    private static final String SNIPER_PERMISSION = "voxelsniper.sniper";
    /**
     * One click can arrive as several packets (use-on-block followed by use-item, swing after a block click), so
     * after a snipe the same player is ignored for this many ticks. The 1.20 Forge port used 500 ms.
     */
    private static final int COOLDOWN_TICKS = 10;

    private final VoxelSniperForge main;
    private final Map<UUID, Integer> lastSnipe = new HashMap<>();
    private final Map<UUID, Integer> lastDrop = new HashMap<>();

    public ForgeVoxelSniperListener(final VoxelSniperForge main) {
        this.main = main;
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public void onRightClickBlock(final PlayerInteractEvent.RightClickBlock event) {
        if (handle(event, Sniper.Action.RIGHT_CLICK_BLOCK, event.getPos(), event.getFace())) {
            event.setCancellationResult(InteractionResult.SUCCESS);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public void onRightClickItem(final PlayerInteractEvent.RightClickItem event) {
        if (handle(event, Sniper.Action.RIGHT_CLICK_AIR, null, null)) {
            event.setCancellationResult(InteractionResult.SUCCESS);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public void onLeftClickBlock(final PlayerInteractEvent.LeftClickBlock event) {
        if (event.getAction() != PlayerInteractEvent.LeftClickBlock.Action.START) return;
        handle(event, Sniper.Action.LEFT_CLICK_BLOCK, event.getPos(), event.getFace());
    }

    /**
     * Called by the swing mixin. Only a swing that hits no block in reach counts as a left click in the air; a swing
     * at a block in reach is already a {@link PlayerInteractEvent.LeftClickBlock}.
     */
    public void onSwing(final ServerPlayer player) {
        if (player.isSpectator()) return;
        // Dropping an item swings the arm too; that is not a click.
        Integer dropped = lastDrop.get(player.getUUID());
        if (dropped != null && dropped == player.server.getTickCount()) return;
        double reach = player.blockInteractionRange();
        var eye = player.getEyePosition();
        var end = eye.add(player.getViewVector(1.0F).scale(reach));
        var hit = player.level().clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
        if (hit.getType() != HitResult.Type.MISS) return;
        snipe(player, player.getMainHandItem(), Sniper.Action.LEFT_CLICK_AIR, null, null);
    }

    private boolean handle(PlayerInteractEvent event, Sniper.Action action, @Nullable BlockPos pos, @Nullable Direction face) {
        if (event.getLevel().isClientSide()) return false;
        if (event.getHand() != InteractionHand.MAIN_HAND) return false;
        if (!(event.getEntity() instanceof ServerPlayer player)) return false;
        if (snipe(player, event.getItemStack(), action, pos, face)) {
            ((ICancellableEvent) event).setCanceled(true);
            return true;
        }
        return false;
    }

    private boolean snipe(ServerPlayer serverPlayer, ItemStack stack, Sniper.Action action, @Nullable BlockPos pos, @Nullable Direction face) {
        IPlayer player = main.getPlayer(serverPlayer);
        if (player == null || !player.hasPermission(SNIPER_PERMISSION)) return false;
        int now = serverPlayer.server.getTickCount();
        Integer last = lastSnipe.get(player.getUniqueId());
        if (last != null && now - last < COOLDOWN_TICKS) {
            // Swallow the duplicate packets of a click that already sniped, but only for sniper tools.
            return player.getSniper().isEnabled() && isTool(player, stack);
        }
        try {
            Sniper sniper = player.getSniper();
            if (sniper.isEnabled() && sniper.snipe(action, getMaterial(stack), getClickedBlock(serverPlayer, pos), getBlockFace(face))) {
                lastSnipe.put(player.getUniqueId(), now);
                return true;
            }
        } catch (final Exception e) {
            main.getLogger().log(java.util.logging.Level.WARNING, "Snipe failed", e);
        }
        return false;
    }

    private static boolean isTool(IPlayer player, ItemStack stack) {
        var material = getMaterial(stack);
        var sniper = player.getSniper();
        var tool = sniper.getSnipeTool(sniper.getToolId(material));
        return tool != null && tool.hasToolAssigned(material);
    }

    private static @Nullable BlockFace getBlockFace(@Nullable Direction face) {
        return face == null ? null : BlockFace.valueOf(face.name());
    }

    private @Nullable IBlock getClickedBlock(ServerPlayer player, @Nullable BlockPos pos) {
        if (pos == null) return null;
        var world = main.getWorld((ServerLevel) player.level());
        return world.getBlock(ForgeLocation.fromForgeBlockPos(world, pos));
    }

    private static VoxelMaterial getMaterial(ItemStack stack) {
        var itemKey = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return new ItemMaterial(stack.getItem(), itemKey.getNamespace(), itemKey.getPath());
    }

    @SubscribeEvent
    public void onItemToss(final ItemTossEvent event) {
        if (event.getPlayer() instanceof ServerPlayer player) {
            lastDrop.put(player.getUUID(), player.server.getTickCount());
        }
    }

    @SubscribeEvent
    public void onPlayerJoin(final PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer serverPlayer)) return;
        IPlayer player = main.getPlayer(serverPlayer);
        if (player == null) return;
        if (player.hasPermission(SNIPER_PERMISSION) && main.getVoxelSniperConfiguration().isMessageOnLoginEnabled()) {
            player.getSniper().displayInfo();
        }
    }

    @SubscribeEvent
    public void onPlayerLeave(final PlayerEvent.PlayerLoggedOutEvent event) {
        lastSnipe.remove(event.getEntity().getUUID());
        lastDrop.remove(event.getEntity().getUUID());
    }
}
