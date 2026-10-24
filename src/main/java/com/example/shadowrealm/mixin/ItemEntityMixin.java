package com.example.shadowrealm.mixin;

import com.example.shadowrealm.SoulManager;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Geister (Spieler ohne Seele) können keine Items aufheben. */
@Mixin(ItemEntity.class)
public abstract class ItemEntityMixin {
	@Inject(method = "playerTouch", at = @At("HEAD"), cancellable = true)
	private void shadowrealm$ghostsCannotPickUp(Player player, CallbackInfo ci) {
		if (player instanceof ServerPlayer serverPlayer && SoulManager.isGhostPlayer(serverPlayer)) {
			ci.cancel();
		}
	}
}
