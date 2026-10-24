package com.example.shadowrealm.client;

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;

/** Zeigt die eigene Seele mit Rang im Inventar, rechts neben dem Offhand-Slot (nur Anzeige). */
public final class InventorySoulLabel {
	private InventorySoulLabel() {
	}

	public static void register() {
		ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
			if (!(screen instanceof InventoryScreen)) {
				return;
			}
			int left = (width - 176) / 2;
			int top = (height - 166) / 2;
			Button label = Button.builder(Component.literal(ClientSoul.shortLabel()), b -> {
			}).bounds(left + 97, top + 61, 54, 18).build();
			label.active = false;
			Screens.getWidgets(screen).add(label);
		});
	}
}
