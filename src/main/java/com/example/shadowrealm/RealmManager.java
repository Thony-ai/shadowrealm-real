package com.example.shadowrealm;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Gesamte Spiellogik: Einsperren, Auswählen, Zurückrufen. */
public final class RealmManager {
	/** Spawnpunkt in der Schattenwelt (Flat-Welt, Oberfläche liegt bei y = 7). */
	private static final double REALM_X = 0.5;
	private static final double REALM_Y = 8.0;
	private static final double REALM_Z = 0.5;

	private static MinecraftServer server;
	private static RealmState state;

	/** Pro Klingenträger: aktuell ausgewähltes Opfer (UUID-String). Nur im Speicher. */
	private static final Map<UUID, String> selection = new HashMap<>();

	private RealmManager() {
	}

	public static void register() {
		ServerLifecycleEvents.SERVER_STARTED.register(s -> {
			server = s;
			state = RealmState.load(s);
			selection.clear();
		});
		ServerLifecycleEvents.SERVER_STOPPING.register(s -> {
			if (state != null) {
				state.save();
			}
		});
		ServerLifecycleEvents.SERVER_STOPPED.register(s -> {
			server = null;
			state = null;
			selection.clear();
		});

		ServerLivingEntityEvents.AFTER_DEATH.register(RealmManager::onDeath);

		// In der Schattenwelt kann niemandem etwas passieren.
		ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) ->
				!(entity instanceof ServerPlayer player && isInRealm(player)));

		// Nach dem Respawn geht es direkt in die Schattenwelt.
		ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
			if (alive || state == null) {
				return;
			}
			RealmState.Entry entry = state.entries.get(newPlayer.getUUID().toString());
			if (entry != null && entry.inRealm) {
				sendToRealm(newPlayer);
			}
		});

		// Wurde jemand zurückgerufen, während er offline war, passiert es beim nächsten Login.
		ServerPlayConnectionEvents.JOIN.register((handler, sender, s) -> {
			if (state == null) {
				return;
			}
			ServerPlayer player = handler.getPlayer();
			RealmState.Entry entry = state.entries.get(player.getUUID().toString());
			if (entry != null && entry.inRealm && entry.recallPending) {
				bringBack(player, entry);
				state.entries.remove(player.getUUID().toString());
				state.save();
			}
		});
	}

	private static boolean isInRealm(ServerPlayer player) {
		return player.level().dimension().equals(ShadowRealm.REALM);
	}

	// ------------------------------------------------------------------ Tod

	private static void onDeath(LivingEntity entity, DamageSource source) {
		if (state == null || !(entity instanceof ServerPlayer victim)) {
			return;
		}
		if (!(source.getEntity() instanceof ServerPlayer killer) || killer == victim) {
			return;
		}
		if (!ShadowBladeItem.isHolding(killer) || isInRealm(victim)) {
			return;
		}

		// Alle Items fallen am Todesort (bei keepInventory würden sie sonst mitkommen).
		victim.getInventory().dropAll();

		RealmState.Entry entry = new RealmState.Entry();
		entry.killer = killer.getUUID();
		entry.name = victim.getName().getString();
		entry.dim = victim.level().dimension().identifier().toString();
		entry.x = victim.getX();
		entry.y = victim.getY();
		entry.z = victim.getZ();
		entry.yRot = victim.getYRot();
		entry.xRot = victim.getXRot();
		entry.gameMode = victim.gameMode.getGameModeForPlayer().getName();
		entry.inRealm = true;
		entry.recallPending = false;

		state.entries.put(victim.getUUID().toString(), entry);
		state.save();
	}

	private static void sendToRealm(ServerPlayer player) {
		ServerLevel realm = server.getLevel(ShadowRealm.REALM);
		if (realm == null) {
			player.sendSystemMessage(Component.literal("Die Schattenwelt konnte nicht geladen werden."));
			return;
		}
		player.getInventory().clearContent();
		player.setGameMode(GameType.ADVENTURE);
		player.teleportTo(realm, REALM_X, REALM_Y, REALM_Z, Set.of(), 0.0F, 0.0F, true);
		player.sendSystemMessage(Component.literal("Du wurdest von der Schattenklinge in die Schattenwelt verbannt..."));
	}

	// ------------------------------------------------------------------ Rückruf

	/** Aktion von der Tastenkombination: 0 = Opfer wählen, 1 = zurückrufen, 2 = alle zurückrufen. */
	public static void handleAction(ServerPlayer wielder, int action) {
		if (state == null || server == null || !ShadowBladeItem.isHolding(wielder)) {
			return;
		}

		List<String> victims = state.victimsOf(wielder.getUUID());
		if (victims.isEmpty()) {
			tell(wielder, "Niemand ist durch deine Klinge in der Schattenwelt.");
			selection.remove(wielder.getUUID());
			return;
		}

		String selected = selection.get(wielder.getUUID());

		switch (action) {
			case 0 -> {
				int next = (victims.indexOf(selected) + 1) % victims.size();
				String key = victims.get(next);
				selection.put(wielder.getUUID(), key);
				tell(wielder, "Ausgewählt: " + state.entries.get(key).name
						+ " (" + (next + 1) + "/" + victims.size() + ")");
			}
			case 1 -> {
				if (selected == null || !victims.contains(selected)) {
					tell(wielder, "Wähle zuerst ein Opfer mit der Rückruf-Taste.");
					return;
				}
				recall(wielder, selected);
				selection.remove(wielder.getUUID());
			}
			case 2 -> {
				for (String key : new ArrayList<>(victims)) {
					recall(wielder, key);
				}
				selection.remove(wielder.getUUID());
			}
			default -> {
			}
		}
	}

	private static void recall(ServerPlayer wielder, String key) {
		RealmState.Entry entry = state.entries.get(key);
		if (entry == null) {
			return;
		}
		ServerPlayer target = server.getPlayerList().getPlayer(UUID.fromString(key));
		if (target != null) {
			bringBack(target, entry);
			state.entries.remove(key);
			tell(wielder, entry.name + " wurde zurückgerufen.");
		} else {
			entry.recallPending = true;
			tell(wielder, entry.name + " ist offline und kommt beim nächsten Login zurück.");
		}
		state.save();
	}

	private static void bringBack(ServerPlayer target, RealmState.Entry entry) {
		ResourceKey<Level> dimKey = ResourceKey.create(Registries.DIMENSION, Identifier.parse(entry.dim));
		ServerLevel dest = server.getLevel(dimKey);
		if (dest == null) {
			dest = server.overworld();
		}
		target.setGameMode(GameType.byName(entry.gameMode, GameType.SURVIVAL));
		target.teleportTo(dest, entry.x, entry.y, entry.z, Set.of(), entry.yRot, entry.xRot, true);
		target.sendSystemMessage(Component.literal("Du wurdest aus der Schattenwelt zurückgerufen."));
	}

	private static void tell(ServerPlayer player, String message) {
		player.sendSystemMessage(Component.literal(message), true);
	}
}
