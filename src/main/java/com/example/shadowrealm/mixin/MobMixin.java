package com.example.shadowrealm.mixin;

import com.example.shadowrealm.ShadowRealm;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** In der Schattenwelt jagen Mobs nichts und niemanden. */
@Mixin(Mob.class)
public abstract class MobMixin {
	@Inject(method = "setTarget", at = @At("HEAD"), cancellable = true)
	private void shadowrealm$noHunting(LivingEntity target, CallbackInfo ci) {
		Entity self = (Entity) (Object) this;
		if (target != null && self.level().dimension().equals(ShadowRealm.REALM)) {
			ci.cancel();
		}
	}
}
