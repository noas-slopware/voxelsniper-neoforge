package com.github.kevindagame.voxelsniperforge.mixin;

import com.github.kevindagame.voxelsniperforge.VoxelSniperForge;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.InteractionHand;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A left click in the air sends nothing but an arm swing to the server. Hand main hand swings to the sniper
 * listener, which checks whether the swing hit a block in reach.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class MixinServerGamePacketListenerImpl {
    @Shadow
    public ServerPlayer player;

    @Inject(method = "handleAnimate", at = @At("TAIL"))
    private void voxelsniper$onSwing(ServerboundSwingPacket packet, CallbackInfo ci) {
        // TAIL runs on the server thread: the method returns early on the network thread after rescheduling itself.
        if (packet.getHand() != InteractionHand.MAIN_HAND) return;
        var listener = VoxelSniperForge.getInstance().getListener();
        if (listener != null) listener.onSwing(this.player);
    }
}
