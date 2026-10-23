package com.example.shadowrealm.client;

import com.example.shadowrealm.RecallActionPayload;
import com.example.shadowrealm.ShadowBladeItem;
import com.example.shadowrealm.ShadowRealm;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.KeyMapping;
import org.lwjgl.glfw.GLFW;

/**
 * Tastenkombination (Standardtaste R), nur mit der Schattenklinge in der Hand:
 *   R           = nächstes Opfer auswählen
 *   Shift + R   = ausgewähltes Opfer zurückrufen
 *   Strg + R    = alle Opfer zurückrufen
 */
public class ShadowRealmClient implements ClientModInitializer {
	private static KeyMapping recallKey;

	@Override
	public void onInitializeClient() {
		KeyMapping.Category category = KeyMapping.Category.register(ShadowRealm.id("main"));

		recallKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key.shadowrealm.recall",
				InputConstants.Type.KEYSYM,
				GLFW.GLFW_KEY_R,
				category));

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (recallKey.consumeClick()) {
				if (client.player == null || !(client.player.getMainHandItem().getItem() instanceof ShadowBladeItem)) {
					continue;
				}
				int action = 0;
				if (client.options.keySprint.isDown()) {
					action = 2;
				} else if (client.options.keyShift.isDown()) {
					action = 1;
				}
				ClientPlayNetworking.send(new RecallActionPayload(action));
			}
		});
	}
}
