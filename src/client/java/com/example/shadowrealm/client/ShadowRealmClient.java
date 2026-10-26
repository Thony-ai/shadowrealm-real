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
 * Alle Tasten sind unter Steuerung > Tastenbelegung > Schattenwelt änderbar:
 *   R = Klinge-Menü, G = Armee, X = Darkness, C = Bund (Elixier-Schwert)
 */
public class ShadowRealmClient implements ClientModInitializer {
	private static KeyMapping menuKey;
	private static KeyMapping armyKey;
	private static KeyMapping darkKey;
	private static KeyMapping bondKey;

	@Override
	public void onInitializeClient() {
		KeyMapping.Category category = KeyMapping.Category.register(ShadowRealm.id("main"));

		menuKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key.shadowrealm.recall",
				InputConstants.Type.KEYSYM,
				GLFW.GLFW_KEY_R,
				category));
		armyKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key.shadowrealm.army", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_G, category));
		darkKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key.shadowrealm.dark", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_X, category));
		bondKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key.shadowrealm.bond", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_C, category));

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
				if (client.player != null && client.player.getMainHandItem().getItem() instanceof ShadowBladeItem) {
					ClientPlayNetworking.send(new RecallActionPayload("open"));
				}
			}
			while (armyKey.consumeClick()) {
				if (client.player != null && client.player.getMainHandItem().getItem() instanceof ShadowBladeItem) {
					ClientPlayNetworking.send(new RecallActionPayload("army_key"));
				}
			}
			while (darkKey.consumeClick()) {
				if (client.player != null && client.player.getMainHandItem().getItem() instanceof ElixirSwordItem) {
					ClientPlayNetworking.send(new RecallActionPayload("elixir_dark"));
				}
			}
			while (bondKey.consumeClick()) {
				if (client.player != null && client.player.getMainHandItem().getItem() instanceof ElixirSwordItem) {
					ClientPlayNetworking.send(new RecallActionPayload("elixir_bond"));
				}
			}
		});
	}
}
