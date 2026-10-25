package com.example.shadowrealm.mixin;

import com.example.shadowrealm.SoulManager;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Geister können keine Blöcke benutzen (Hebel, Knöpfe, Türen ...) und nicht mit Wesen interagieren. */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerGamePacketListenerImplMixin {
	@Inject(method = "handleUseItemOn", at = @At("HEAD"), cancellable = true)
	private void shadowrealm$ghostUseBlock(ServerboundUseItemOnPacket packet, CallbackInfo ci) {
		ServerGamePacketListenerImpl self = (ServerGamePacketListenerImpl) (Object) this;
		if (SoulManager.ghostBlocked(self.getPlayer(), packet.getHitResult().getBlockPos())) {
			ci.cancel();
		}
	}

	@Inject(method = "handleInteract", at = @At("HEAD"), cancellable = true)
	private void shadowrealm$ghostInteract(ServerboundInteractPacket packet, CallbackInfo ci) {
		ServerGamePacketListenerImpl self = (ServerGamePacketListenerImpl) (Object) this;
		if (SoulManager.isGhostPlayer(self.getPlayer())) {
			ci.cancel();
		}
	}
}
