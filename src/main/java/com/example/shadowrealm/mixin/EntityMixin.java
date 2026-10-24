package com.example.shadowrealm.mixin;

import com.example.shadowrealm.SoulManager;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Geister lösen keine Druckplatten, Stolperdrähte usw. aus. */
@Mixin(Entity.class)
public abstract class EntityMixin {
	@Inject(method = "isIgnoringBlockTriggers", at = @At("HEAD"), cancellable = true)
	private void shadowrealm$ghostNoTriggers(CallbackInfoReturnable<Boolean> cir) {
		if ((Object) this instanceof ServerPlayer player && SoulManager.isGhostPlayer(player)) {
			cir.setReturnValue(true);
		}
	}
}
