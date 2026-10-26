package com.example.shadowrealm;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Merkt sich Gefangene (Spieler und Mobs), Besitzer der Klinge und Rückkehr-Aufträge.
 * Wird als JSON im Weltordner gespeichert (shadowrealm_state.json).
 */
public final class RealmState {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	/** Ein Gefangener: Spieler ("player") oder benanntes Tier/Mob ("mob"). */
	public static final class Entry {
		/** Spieler: UUID des Spielers. Mob: zufällige UUID. */
		public String key;
		public String type;
		/** UUID des Klingenbesitzers, der ihn gefangen hat */
		public String killer;
		public String name;
		/** nur Mobs: Entity-Typ, z. B. "minecraft:wolf" */
		public String mobType;
		/** Dimension des Todesorts, z. B. "minecraft:overworld" */
		public String dim;
		public double x;
		public double y;
		public double z;
		public float yRot;
		public float xRot;
		/** nur Spieler: Spielmodus vor dem Tod */
		public String gameMode;
		/** nur Spieler: gerade als Soldat der Armee in der normalen Welt */
		public boolean inArmy;
		/** UUID des Klingenträgers, dessen Armee er gerade dient */
		public String armyOwner;
		/** Der Besitzer ist gestorben: jeder Klingenträger kann ihn herausholen, ohne Preis */
		public boolean orphan;
	}

	/** Daten eines Klingenbesitzers. */
	public static final class Owner {
		public String name;
		/** Seele ist als Preis genommen (solange mindestens ein Gefangener in der Schattenwelt ist) */
		public boolean soulTaken;
		/** Besitzer soll sterben, war aber offline */
		public boolean pendingDeath;
		/** Besitzer hat die Schattenwelt selbst betreten */
		public boolean inRealm;
		public String retDim;
		public double retX;
		public double retY;
		public double retZ;
		public float retYRot;
		public float retXRot;
		/** Spielmodus vor dem Betreten des Schattens */
		public String retMode;
	}

	/** Seele eines Spielers: Rang (Karma -100..100), vorhanden oder verloren (dann ist er ein Geist). */
	public static final class SoulInfo {
		public String name;
		public boolean hasSoul = true;
		public int karma;
		/** Anzahl der mit dem Elixier-Schwert gezogenen Seelen */
		public int stolen;
		/** eine ganz bösartige Seele wurde gezogen */
		public boolean evilSoul;
		/** das Buch der Schatten wurde schon übergeben */
		public boolean bookGiven;
	}

	/** Leiche eines Spielers, der seine Seele verloren hat. Die Beute liegt als markierte Items daneben. */
	public static final class Corpse {
		public String id;
		public String victim;
		public String name;
		public String dim;
		public double x;
		public double y;
		public double z;
		/** Was bei der Leiche lag: Item-ID -> Anzahl (um Gestohlenes zurückzuholen) */
		public Map<String, Integer> lootCounts = new LinkedHashMap<>();
	}

	/** Eine Seelen-Kiste (am Welt-Spawn) mit der Seele des Besitzers. */
	public static final class SoulChest {
		public int x;
		public int y;
		public int z;
		public String owner;
		public String ownerName;
	}

	/** Wiederbelebung eines Offline-Spielers beim nächsten Login. */
	public static final class Revive {
		public String dim;
		public double x;
		public double y;
		public double z;
	}

	public static final class Data {
		/** Fortschritt beim Bau der Schattenwelt (Anzahl fertiger Abschnitte) */
		public int builtSteps;
		public int buildVersion;
		public boolean realmBuildDone;
		public Map<String, SoulInfo> souls = new LinkedHashMap<>();
		public Map<String, Corpse> corpses = new LinkedHashMap<>();
		public List<SoulChest> soulChests = new ArrayList<>();
		public Map<String, Revive> pendingRevives = new LinkedHashMap<>();
		/** Schlüssel = Entry.key */
		public Map<String, Entry> entries = new LinkedHashMap<>();
		/** Schlüssel = UUID des Besitzers */
		public Map<String, Owner> owners = new LinkedHashMap<>();
		/** Zurückgerufene Spieler, die offline sind: kommen beim nächsten Login zurück */
		public Map<String, Entry> pendingReturns = new LinkedHashMap<>();
	}

	public Data data = new Data();

	private final Path file;

	private RealmState(Path file) {
		this.file = file;
	}

	public static RealmState load(MinecraftServer server) {
		Path file = server.getWorldPath(LevelResource.ROOT).resolve("shadowrealm_state.json");
		RealmState state = new RealmState(file);
		if (Files.exists(file)) {
			try {
				Data loaded = GSON.fromJson(Files.readString(file), Data.class);
				if (loaded != null) {
					state.data = loaded;
				}
			} catch (IOException | RuntimeException e) {
				System.err.println("[shadowrealm] Konnte " + file + " nicht lesen: " + e);
			}
		}
		if (state.data.entries == null) {
			state.data.entries = new LinkedHashMap<>();
		}
		if (state.data.owners == null) {
			state.data.owners = new LinkedHashMap<>();
		}
		if (state.data.souls == null) {
			state.data.souls = new LinkedHashMap<>();
		}
		if (state.data.corpses == null) {
			state.data.corpses = new LinkedHashMap<>();
		}
		if (state.data.soulChests == null) {
			state.data.soulChests = new ArrayList<>();
		}
		if (state.data.pendingRevives == null) {
			state.data.pendingRevives = new LinkedHashMap<>();
		}
		if (state.data.pendingReturns == null) {
			state.data.pendingReturns = new LinkedHashMap<>();
		}
		// Nach einem Neustart gibt es keine laufende Armee mehr.
		for (Entry e : state.data.entries.values()) {
			e.inArmy = false;
		}
		return state;
	}

	public void save() {
		try {
			Files.writeString(file, GSON.toJson(data, Data.class));
		} catch (IOException e) {
			System.err.println("[shadowrealm] Konnte " + file + " nicht speichern: " + e);
		}
	}

	public Owner owner(String uuid) {
		return data.owners.computeIfAbsent(uuid, k -> new Owner());
	}

	/** Anzahl der Gefangenen, die noch zum Preis des Besitzers zählen (herrenlose nicht). */
	public int countOf(String killer) {
		int n = 0;
		for (Entry e : data.entries.values()) {
			if (killer.equals(e.killer) && !e.orphan) {
				n++;
			}
		}
		return n;
	}

	/** Gefangene, die ein Klingenträger sehen und zurückrufen darf: seine eigenen plus alle herrenlosen. */
	public List<Entry> entriesOf(String viewer, String type) {
		List<Entry> list = new ArrayList<>();
		for (Entry e : data.entries.values()) {
			if (type.equals(e.type) && (viewer.equals(e.killer) || e.orphan)) {
				list.add(e);
			}
		}
		list.sort(Comparator.comparing((Entry e) -> String.valueOf(e.name).toLowerCase()));
		return list;
	}

	/** Nur die eigenen, noch nicht herrenlosen Gefangenen eines Besitzers. */
	public List<Entry> ownEntries(String owner) {
		List<Entry> list = new ArrayList<>();
		for (Entry e : data.entries.values()) {
			if (owner.equals(e.killer) && !e.orphan) {
				list.add(e);
			}
		}
		return list;
	}
}
