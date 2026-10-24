package com.example.shadowrealm.client;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;

import java.lang.reflect.Field;

/**
 * Zeigt die eigene Seele mit Rang über dem Inventar-Fenster (nur Anzeige).
 * Die Position folgt dem Fenster, auch wenn das Rezeptbuch geöffnet wird.
 */
public final class InventorySoulLabel {
	private static Button label;

	private InventorySoulLabel() {
	}

	public static void register() {
		ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
			if (!(screen instanceof InventoryScreen)) {
				label = null;
				return;
			}
			label = Button.builder(Component.literal(ClientSoul.label()), b -> {
			}).bounds((width - 176) / 2, (height - 166) / 2 - 22, 120, 18).build();
			label.active = false;
			Screens.getWidgets(screen).add(label);
		});

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			if (label == null || !(client.gui.screen() instanceof InventoryScreen screen)) {
				return;
			}
			int left = (client.getWindow().getGuiScaledWidth() - 176) / 2;
			int top = (client.getWindow().getGuiScaledHeight() - 166) / 2;
			Integer realLeft = readField(screen, "leftPos");
			Integer realTop = readField(screen, "topPos");
			if (realLeft != null) {
				left = realLeft;
			}
			if (realTop != null) {
				top = realTop;
			}
			label.setX(left);
			label.setY(top - 22);
			label.setMessage(Component.literal(ClientSoul.label()));
		});
	}

	private static Integer readField(AbstractContainerScreen<?> screen, String name) {
		try {
			Field field = AbstractContainerScreen.class.getDeclaredField(name);
			field.setAccessible(true);
			return field.getInt(screen);
		} catch (ReflectiveOperationException | RuntimeException e) {
			return null;
		}
	}
}
