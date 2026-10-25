package com.example.shadowrealm.client;

import com.example.shadowrealm.ElixirSwordItem;
import com.example.shadowrealm.MenuData;
import com.example.shadowrealm.MenuPayload;
import com.example.shadowrealm.RecallActionPayload;
import com.example.shadowrealm.ShadowBladeItem;
import com.example.shadowrealm.ShadowRealm;
import com.example.shadowrealm.SoulPayload;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

/**
 * Taste R (nur mit der Schattenklinge in der Hand):
 *   R         = Menü öffnen
 *   Mit dem Elixier-Schwert: R = Darkness, Shift + R = Bund
 *   Shift + R = Armee rufen (wenn im Menü geladen) bzw. zurückschicken
 */
public class ShadowRealmClient implements ClientModInitializer {
	private static KeyMapping menuKey;

	@Override
	public void onInitializeClient() {
		KeyMapping.Category category = KeyMapping.Category.register(ShadowRealm.id("main"));

		menuKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key.shadowrealm.recall",
				InputConstants.Type.KEYSYM,
				GLFW.GLFW_KEY_R,
				category));

		// Server schickt den Menü-Inhalt: neues Menü öffnen oder das offene aktualisieren.
		ClientPlayNetworking.registerGlobalReceiver(MenuPayload.TYPE, (payload, context) -> {
			Minecraft minecraft = context.client();
			MenuData data = MenuData.fromJson(payload.json());
			if (minecraft.gui.screen() instanceof ShadowMenuScreen screen) {
				screen.update(data);
			} else {
				minecraft.gui.setScreen(new ShadowMenuScreen(data));
			}
		});

		ClientPlayNetworking.registerGlobalReceiver(SoulPayload.TYPE,
				(payload, context) -> ClientSoul.apply(payload.text()));

		SoulHud.register();
		InventorySoulLabel.register();

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (menuKey.consumeClick()) {
				if (client.player == null) {
					continue;
				}
				if (client.player.getMainHandItem().getItem() instanceof ElixirSwordItem) {
					ClientPlayNetworking.send(new RecallActionPayload(
							client.options.keyShift.isDown() ? "elixir_bond" : "elixir_dark"));
					continue;
				}
				if (!(client.player.getMainHandItem().getItem() instanceof ShadowBladeItem)) {
					continue;
				}
				String action = client.options.keyShift.isDown() ? "army_key" : "open";
				ClientPlayNetworking.send(new RecallActionPayload(action));
			}
		});
	}
}
