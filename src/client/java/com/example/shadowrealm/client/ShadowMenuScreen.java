package com.example.shadowrealm.client;

import com.example.shadowrealm.MenuData;
import com.example.shadowrealm.RecallActionPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Menü der Schattenklinge. Besteht nur aus Knöpfen (kein eigenes Zeichnen nötig):
 * oben der Seelen-Status, in der Mitte die Gefangenen (getrennt nach Spielern und Mobs),
 * unten die festen Aktionen.
 */
public class ShadowMenuScreen extends Screen {
	private static final int WIDTH = 260;
	private static final int ROW = 24;
	private static final int BOTTOM_ROWS = 4;

	private static int rememberedPage = 0;

	private MenuData data;
	private int page = rememberedPage;

	private record Row(Component label, String action) {
	}

	public ShadowMenuScreen(MenuData data) {
		super(Component.translatable("gui.shadowrealm.title"));
		this.data = data;
	}

	/** Neue Daten vom Server übernehmen und das Menü neu aufbauen. */
	public void update(MenuData newData) {
		this.data = newData;
		this.clearWidgets();
		this.init();
	}

	// Ohne @Override, damit es auch kompiliert, falls sich der Name in einer Version ändert.
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	protected void init() {
		int left = (this.width - WIDTH) / 2;

		// Status (oben, deaktivierter Knopf als Textzeile)
		addLabel(Component.translatable(data.soulTaken ? "gui.shadowrealm.soul_taken" : "gui.shadowrealm.soul_kept"),
				left, 8, WIDTH);

		// Zeilen der Liste
		int total = data.players.size() + data.mobs.size();
		List<Row> rows = new ArrayList<>();
		rows.add(new Row(Component.translatable("gui.shadowrealm.players", data.players.size()), null));
		for (MenuData.Item item : data.players) {
			rows.add(new Row(recallLabel(item.name, item.orphan), "recall:" + item.key));
		}
		rows.add(new Row(Component.translatable("gui.shadowrealm.mobs", data.mobs.size()), null));
		for (MenuData.Item item : data.mobs) {
			rows.add(new Row(recallLabel(item.name + " (" + item.type + ")", item.orphan), "recall:" + item.key));
		}
		if (total == 0) {
			rows.add(new Row(Component.translatable("gui.shadowrealm.empty"), null));
		}

		// Platz für die Liste berechnen
		int listTop = 8 + 26;
		int bottomTop = this.height - BOTTOM_ROWS * ROW - 6;
		int perPage = Math.max(1, (bottomTop - listTop - 4) / ROW);
		int pages = Math.max(1, (rows.size() + perPage - 1) / perPage);
		page = Math.max(0, Math.min(page, pages - 1));
		rememberedPage = page;

		int start = page * perPage;
		int end = Math.min(rows.size(), start + perPage);
		for (int i = start; i < end; i++) {
			Row row = rows.get(i);
			int y = listTop + (i - start) * ROW;
			if (row.action() == null) {
				addLabel(row.label(), left, y, WIDTH);
			} else {
				String action = row.action();
				addButton(row.label(), left, y, WIDTH, true, () -> send(action));
			}
		}

		int half = WIDTH / 2 - 2;
		int y = bottomTop;

		// Zeile 1: Seiten wechseln
		final int lastPage = pages - 1;
		addButton(Component.literal("<"), left, y, half, page > 0, () -> {
			page--;
			rememberedPage = page;
			this.clearWidgets();
			this.init();
		});
		addButton(Component.literal((page + 1) + "/" + pages + "  >"), left + half + 4, y, half, page < lastPage, () -> {
			page++;
			rememberedPage = page;
			this.clearWidgets();
			this.init();
		});

		// Zeile 2: alle zurückrufen | Schatten betreten/verlassen
		y += ROW;
		addButton(Component.translatable(data.ownCount > 0 ? "gui.shadowrealm.recall_all" : "gui.shadowrealm.recall_all_free"),
				left, y, half, total > 0, () -> send("recallall"));
		addButton(Component.translatable(data.inRealm ? "gui.shadowrealm.leave_shadow" : "gui.shadowrealm.enter_shadow"),
				left + half + 4, y, half, true, () -> send(data.inRealm ? "leave" : "enter"));

		// Zeile 3: Armee
		y += ROW;
		if (data.armyActive) {
			addButton(Component.translatable("gui.shadowrealm.army_dismiss"), left, y, WIDTH, true,
					() -> send("army_dismiss"));
		} else if (data.armed) {
			addButton(Component.translatable("gui.shadowrealm.army_armed"), left, y, WIDTH, false, () -> {
			});
		} else {
			addButton(Component.translatable("gui.shadowrealm.army_arm"), left, y, WIDTH,
					!data.players.isEmpty() && !data.inRealm, () -> send("army_arm"));
		}

		// Zeile 4: schließen
		y += ROW;
		addButton(Component.translatable("gui.shadowrealm.close"), left, y, WIDTH, true, this::onClose);
	}

	/** Das Label warnt, wenn dieser Rückruf den letzten eigenen Gefangenen holt (dann stirbt man). */
	private Component recallLabel(String name, boolean orphan) {
		if (orphan) {
			return Component.translatable("gui.shadowrealm.recall_orphan", name);
		}
		return Component.translatable(data.ownCount == 1 ? "gui.shadowrealm.recall_last" : "gui.shadowrealm.recall_one", name);
	}

	private void addLabel(Component text, int x, int y, int width) {
		Button label = Button.builder(text, b -> {
		}).bounds(x, y, width, 20).build();
		label.active = false;
		this.addRenderableWidget(label);
	}

	private void addButton(Component text, int x, int y, int width, boolean enabled, Runnable action) {
		Button button = Button.builder(text, b -> action.run()).bounds(x, y, width, 20).build();
		button.active = enabled;
		this.addRenderableWidget(button);
	}

	private static void send(String action) {
		ClientPlayNetworking.send(new RecallActionPayload(action));
	}
}
