package com.example.shadowrealm;

import com.google.gson.Gson;

import java.util.ArrayList;
import java.util.List;

/** Daten, die der Server an das Menü des Clients schickt (als JSON-Text). */
public final class MenuData {
	public List<Item> players = new ArrayList<>();
	public List<Item> mobs = new ArrayList<>();
	public boolean soulTaken;
	public boolean inRealm;
	public boolean armed;
	public boolean armyActive;
	/** Anzahl eigener Gefangener (nur die kosten beim Zurückrufen den Preis) */
	public int ownCount;

	public static final class Item {
		public String key;
		public String name;
		/** "player" oder bei Mobs der Typ, z. B. "wolf" */
		public String type;
		/** herrenlos: Besitzer ist gestorben, Rückruf kostet nichts */
		public boolean orphan;
	}

	private static final Gson GSON = new Gson();

	public String toJson() {
		return GSON.toJson(this);
	}

	public static MenuData fromJson(String json) {
		try {
			MenuData data = GSON.fromJson(json, MenuData.class);
			if (data != null) {
				if (data.players == null) {
					data.players = new ArrayList<>();
				}
				if (data.mobs == null) {
					data.mobs = new ArrayList<>();
				}
				return data;
			}
		} catch (RuntimeException ignored) {
		}
		return new MenuData();
	}
}
