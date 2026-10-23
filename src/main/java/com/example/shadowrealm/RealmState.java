package com.example.shadowrealm;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Merkt sich, wer in der Schattenwelt gefangen ist, wer ihn dort hingeschickt hat
 * und wo er gestorben ist. Wird als JSON im Weltordner gespeichert.
 */
public final class RealmState {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Type MAP_TYPE = new TypeToken<LinkedHashMap<String, Entry>>() { }.getType();

	public static final class Entry {
		public UUID killer;
		public String name;
		/** Dimension des Todesorts, z. B. "minecraft:overworld" */
		public String dim;
		public double x;
		public double y;
		public double z;
		public float yRot;
		public float xRot;
		/** Spielmodus vor dem Tod, wird beim Rückruf wiederhergestellt */
		public String gameMode;
		public boolean inRealm;
		/** true, wenn der Spieler zurückgerufen wurde, aber gerade offline ist */
		public boolean recallPending;
	}

	/** Schlüssel = UUID des Opfers als String */
	public final Map<String, Entry> entries = new LinkedHashMap<>();

	private final Path file;

	private RealmState(Path file) {
		this.file = file;
	}

	public static RealmState load(MinecraftServer server) {
		Path file = server.getWorldPath(LevelResource.ROOT).resolve("shadowrealm_state.json");
		RealmState state = new RealmState(file);
		if (Files.exists(file)) {
			try {
				Map<String, Entry> loaded = GSON.fromJson(Files.readString(file), MAP_TYPE);
				if (loaded != null) {
					state.entries.putAll(loaded);
				}
			} catch (IOException | RuntimeException e) {
				System.err.println("[shadowrealm] Konnte " + file + " nicht lesen: " + e);
			}
		}
		return state;
	}

	public void save() {
		try {
			Files.writeString(file, GSON.toJson(entries, MAP_TYPE));
		} catch (IOException e) {
			System.err.println("[shadowrealm] Konnte " + file + " nicht speichern: " + e);
		}
	}

	/** Alle Opfer dieses Spielers, die gerade in der Schattenwelt sind (alphabetisch). */
	public List<String> victimsOf(UUID killer) {
		List<Map.Entry<String, Entry>> list = new ArrayList<>();
		for (Map.Entry<String, Entry> e : entries.entrySet()) {
			if (e.getValue().inRealm && killer.equals(e.getValue().killer)) {
				list.add(e);
			}
		}
		list.sort(Comparator.comparing((Map.Entry<String, Entry> e) -> String.valueOf(e.getValue().name).toLowerCase()));
		List<String> keys = new ArrayList<>();
		for (Map.Entry<String, Entry> e : list) {
			keys.add(e.getKey());
		}
		return keys;
	}
}
