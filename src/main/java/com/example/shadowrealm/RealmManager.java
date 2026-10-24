package com.example.shadowrealm;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Gesamte Spiellogik:
 * - Tod mit der Klinge = Gefangenschaft in der Schattenwelt (Spieler und benannte Mobs)
 * - Preis: die Seele des Besitzers, solange jemand gefangen ist
 * - Menü-Aktionen: zurückrufen, Schatten betreten/verlassen, Armee
 */
public final class RealmManager {
	/** Spawnpunkt in der Schattenwelt (Flat-Welt, Oberfläche liegt bei y = 7). */
	private static final double REALM_X = 0.5;
	private static final double REALM_Y = 8.0;
	private static final double REALM_Z = 0.5;

	private static final String MOB_TAG = "shadowcaptive:";
	private static final String KIT_NAME = "Schatten-Kit";

	/** Wie lange die Armee kostenlos da sein darf (30 Sekunden). */
	private static final long ARMY_GRACE_TICKS = 600;
	/** Danach verliert der Besitzer alle 10 Sekunden ein Herz. */
	private static final long ARMY_DRAIN_INTERVAL = 200;
	/** Soldaten werden zum Besitzer zurückgeholt, wenn sie weiter weg sind. */
	private static final double ARMY_LEASH = 24.0;
	/** So lange gilt ein vom Besitzer angegriffenes Ziel für die Armee (15 Sekunden). */
	private static final long TARGET_MEMORY_TICKS = 300;

	private static MinecraftServer server;
	private static RealmState state;

	/** Besitzer, deren Armee "geladen" ist (nur im Speicher). */
	private static final Set<UUID> armed = new HashSet<>();
	/** Laufende Armeen, Schlüssel = UUID des Besitzers (nur im Speicher). */
	private static final Map<UUID, Army> armies = new HashMap<>();
	/** Spielmodus, der nach dem Tod in der Schattenwelt wiederhergestellt wird. */
	private static final Map<UUID, String> restoreMode = new HashMap<>();

	private static final class Army {
		long nextDrain;
		UUID target;
		long targetTick;
		final List<String> keys = new ArrayList<>();
	}

	private RealmManager() {
	}

	/** Tags einer Entity. Per Reflection, weil die Methode je nach Version anders heißt. */
	@SuppressWarnings("unchecked")
	static Set<String> tagsOf(Entity entity) {
		for (String name : new String[]{"entityTags", "getTags"}) {
			try {
				Object result = entity.getClass().getMethod(name).invoke(entity);
				if (result instanceof Set<?> set) {
					return (Set<String>) set;
				}
			} catch (ReflectiveOperationException | RuntimeException ignored) {
			}
		}
		return new HashSet<>();
	}

	static Optional<EntityType<?>> mobTypeOf(String key) {
		try {
			return net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getOptional(Identifier.parse(key));
		} catch (RuntimeException e) {
			return Optional.empty();
		}
	}

	static MinecraftServer server() {
		return server;
	}

	static RealmState state() {
		return state;
	}

	// ================================================================== Setup

	public static void register() {
		ServerLifecycleEvents.SERVER_STARTED.register(s -> {
			server = s;
			state = RealmState.load(s);
			armed.clear();
			armies.clear();
			restoreMode.clear();
			forceRealmChunks(s);
		});
		ServerLifecycleEvents.SERVER_STOPPING.register(s -> {
			if (state != null) {
				state.save();
			}
		});
		ServerLifecycleEvents.SERVER_STOPPED.register(s -> {
			server = null;
			state = null;
			armed.clear();
			armies.clear();
			restoreMode.clear();
		});

		ServerLivingEntityEvents.AFTER_DEATH.register(RealmManager::onDeath);
		ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> allowDamage(entity, source));

		ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
			if (alive || state == null) {
				return;
			}
			String id = id(newPlayer);
			RealmState.Entry entry = state.data.entries.get(id);
			if (entry != null && "player".equals(entry.type)) {
				sendToRealm(newPlayer);
				return;
			}
			String mode = restoreMode.remove(newPlayer.getUUID());
			if (mode != null) {
				newPlayer.setGameMode(GameType.byName(mode, GameType.SURVIVAL));
			}
			RealmState.Owner owner = state.data.owners.get(id);
			if (owner != null && owner.inRealm) {
				owner.inRealm = false;
				state.save();
			}
		});

		ServerPlayConnectionEvents.JOIN.register((handler, sender, s) -> {
			if (state == null) {
				return;
			}
			ServerPlayer player = handler.getPlayer();
			String id = id(player);

			RealmState.Entry pending = state.data.pendingReturns.remove(id);
			if (pending != null) {
				bringBack(player, pending);
				state.save();
			}

			RealmState.Entry captive = state.data.entries.get(id);
			if (captive != null && "player".equals(captive.type)) {
				sendToRealm(player);
			}

			RealmState.Owner owner = state.data.owners.get(id);
			if (owner != null && owner.pendingDeath) {
				owner.pendingDeath = false;
				state.save();
				s.execute(() -> killNow(player));
			}
		});

		ServerPlayConnectionEvents.DISCONNECT.register((handler, s) -> {
			if (state == null) {
				return;
			}
			ServerPlayer player = handler.getPlayer();
			UUID uuid = player.getUUID();
			armed.remove(uuid);
			if (armies.containsKey(uuid)) {
				dismissArmy(uuid);
			}
			// Ein Soldat, der die Verbindung trennt, verliert sofort sein Kit.
			RealmState.Entry entry = state.data.entries.get(id(player));
			if (entry != null && entry.inArmy) {
				entry.inArmy = false;
				player.getInventory().clearContent();
				Army army = armies.get(parseUuid(entry.armyOwner));
				if (army != null) {
					army.keys.remove(entry.key);
				}
				state.save();
			}
		});

		ServerTickEvents.END_SERVER_TICK.register(s -> {
			if (state == null || server == null) {
				return;
			}
			long tick = s.getTickCount();
			if (tick % 10 == 0) {
				tickArmies(tick);
			}
			if (tick % 100 == 0) {
				cleanupRealmMobs();
			}
			SoulManager.tick(tick);
		});

		SoulManager.register();
	}

	private static void forceRealmChunks(MinecraftServer s) {
		try {
			ServerLevel realm = s.getLevel(ShadowRealm.REALM);
			if (realm != null) {
				for (int cx = -1; cx <= 0; cx++) {
					for (int cz = -1; cz <= 0; cz++) {
						realm.setChunkForced(cx, cz, true);
					}
				}
			}
		} catch (RuntimeException e) {
			System.err.println("[shadowrealm] Konnte Chunks der Schattenwelt nicht laden: " + e);
		}
	}

	// ================================================================== Hilfsfunktionen

	static String id(ServerPlayer player) {
		return player.getUUID().toString();
	}

	static UUID parseUuid(String s) {
		try {
			return UUID.fromString(s);
		} catch (RuntimeException e) {
			return new UUID(0L, 0L);
		}
	}

	private static boolean isBlade(ServerPlayer player) {
		return ShadowBladeItem.isHolding(player);
	}

	static boolean inRealmDim(Entity entity) {
		return entity.level().dimension().equals(ShadowRealm.REALM);
	}

	private static boolean isSoldier(ServerPlayer player) {
		RealmState.Entry entry = state.data.entries.get(id(player));
		return entry != null && entry.inArmy;
	}

	static ServerLevel levelOf(String dim) {
		try {
			ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION, Identifier.parse(dim));
			ServerLevel level = server.getLevel(key);
			if (level != null) {
				return level;
			}
		} catch (RuntimeException ignored) {
		}
		return server.overworld();
	}

	static void tell(ServerPlayer player, String message) {
		player.sendSystemMessage(Component.literal(message), true);
	}

	static void chat(ServerPlayer player, String message) {
		player.sendSystemMessage(Component.literal(message), false);
	}

	private static boolean isKit(ItemStack stack) {
		Component name = stack.get(DataComponents.CUSTOM_NAME);
		return name != null && KIT_NAME.equals(name.getString());
	}

	// ================================================================== Schaden

	private static boolean allowDamage(LivingEntity entity, DamageSource source) {
		if (state == null || server == null) {
			return true;
		}
		boolean bypass = source.is(DamageTypeTags.BYPASSES_INVULNERABILITY);
		Entity attacker = source.getEntity();

		// Soldaten der Armee sind verwundbar, aber der Besitzer und andere Soldaten dürfen sie nicht verletzen.
		if (!bypass && entity instanceof ServerPlayer soldier && isSoldier(soldier)) {
			if (attacker instanceof ServerPlayer friend) {
				RealmState.Entry soldierEntry = state.data.entries.get(id(soldier));
				if (friend.getUUID().equals(parseUuid(soldierEntry.armyOwner)) || isSoldier(friend)) {
					return false;
				}
			}
			return true;
		}

		// In der Schattenwelt kann nur ein Spieler mit der Klinge in der Hand jemandem etwas tun.
		if (!bypass && inRealmDim(entity)) {
			return attacker instanceof ServerPlayer wielder && isBlade(wielder);
		}

		if (attacker instanceof ServerPlayer attackingPlayer) {
			// Soldaten dürfen nur das Ziel angreifen, das der Besitzer zuletzt angegriffen hat.
			if (isSoldier(attackingPlayer)) {
				RealmState.Entry entry = state.data.entries.get(id(attackingPlayer));
				Army army = armies.get(parseUuid(entry.armyOwner));
				return army != null
						&& army.target != null
						&& army.target.equals(entity.getUUID())
						&& server.getTickCount() - army.targetTick <= TARGET_MEMORY_TICKS;
			}
			// Besitzer mit laufender Armee: Ziel merken.
			Army army = armies.get(attackingPlayer.getUUID());
			if (army != null && entity != attackingPlayer) {
				army.target = entity.getUUID();
				army.targetTick = server.getTickCount();
			}
		}
		return true;
	}

	// ================================================================== Tod

	private static void onDeath(LivingEntity entity, DamageSource source) {
		if (state == null || server == null) {
			return;
		}
		if (entity instanceof ServerPlayer victim) {
			onPlayerDeath(victim, source);
		} else {
			onMobDeath(entity, source);
		}
	}

	private static void onPlayerDeath(ServerPlayer victim, DamageSource source) {
		String vid = id(victim);

		RealmState.Entry captive = state.data.entries.get(vid);
		if (captive != null && "player".equals(captive.type)) {
			// 1a) Ein Soldat der Armee ist gefallen: Das Kit verschwindet, er kehrt in die Schattenwelt zurück.
			if (captive.inArmy) {
				captive.inArmy = false;
				Army army = armies.get(parseUuid(captive.armyOwner));
				if (army != null) {
					army.keys.remove(vid);
					if (army.keys.isEmpty()) {
						armies.remove(parseUuid(captive.armyOwner));
					}
				}
				removeDroppedKit(victim);
				ServerPlayer armyOwner = server.getPlayerList().getPlayer(parseUuid(captive.armyOwner));
				if (armyOwner != null) {
					tell(armyOwner, captive.name + " ist gefallen und kehrt in den Schatten zurück.");
				}
				state.save();
				return; // Beim Respawn wird er automatisch zurück in die Schattenwelt geschickt.
			}

			// 1b) Ein Gefangener wurde in der Schattenwelt getötet: er ist frei und respawnt normal.
			state.data.entries.remove(vid);
			restoreMode.put(victim.getUUID(), captive.gameMode);
			state.save();
			if (!captive.orphan) {
				afterRemoval(captive.killer, null, false);
			}
			return;
		}

		// 2) Ein Besitzer stirbt mit Gefangenen: sie bleiben in der Schattenwelt, bis jemand mit
		//    einer Klinge sie herausholt. Die Seele landet in einer Kiste am Welt-Spawn.
		if (state.countOf(vid) > 0) {
			RealmState.Owner dyingOwner = state.data.owners.get(vid);
			if (dyingOwner != null && dyingOwner.soulTaken) {
				SoulManager.loseSoulAfterDeath(victim);
			}
			orphanAll(vid);
		}
		dismissArmy(victim.getUUID());

		// 3) Wurde das Opfer mit der Klinge erschlagen, kommt es in die Schattenwelt.
		if (source.getEntity() instanceof ServerPlayer killer
				&& killer != victim
				&& isBlade(killer)
				&& !inRealmDim(victim)) {
			capturePlayer(killer, victim);
		}
	}

	private static void onMobDeath(LivingEntity entity, DamageSource source) {
		// Ein gefangener Mob wurde in der Schattenwelt getötet: er ist endgültig tot.
		String capturedKey = captiveKeyOf(entity);
		if (capturedKey != null) {
			RealmState.Entry entry = state.data.entries.remove(capturedKey);
			if (entry != null) {
				state.save();
				if (!entry.orphan) {
					afterRemoval(entry.killer, null, false);
				}
			}
			return;
		}

		// Benannte Tiere/Mobs, die mit der Klinge erschlagen werden, kommen in die Schattenwelt.
		if (!(entity instanceof Mob) || !entity.hasCustomName()) {
			return;
		}
		if (source.getEntity() instanceof ServerPlayer killer && isBlade(killer) && !inRealmDim(entity)) {
			captureMob(killer, entity);
		}
	}

	private static String captiveKeyOf(Entity entity) {
		for (String tag : tagsOf(entity)) {
			if (tag.startsWith(MOB_TAG)) {
				return tag.substring(MOB_TAG.length());
			}
		}
		return null;
	}

	// ================================================================== Gefangennahme

	private static void capturePlayer(ServerPlayer killer, ServerPlayer victim) {
		// Alle Items fallen am Todesort (bei keepInventory würden sie sonst mitkommen).
		victim.getInventory().dropAll();

		RealmState.Entry entry = new RealmState.Entry();
		entry.key = id(victim);
		entry.type = "player";
		entry.killer = id(killer);
		entry.name = victim.getName().getString();
		entry.dim = victim.level().dimension().identifier().toString();
		entry.x = victim.getX();
		entry.y = victim.getY();
		entry.z = victim.getZ();
		entry.yRot = victim.getYRot();
		entry.xRot = victim.getXRot();
		entry.gameMode = victim.gameMode.getGameModeForPlayer().getName();
		registerCapture(killer, entry);
	}

	private static void captureMob(ServerPlayer killer, LivingEntity mob) {
		Component customName = mob.getCustomName();
		RealmState.Entry entry = new RealmState.Entry();
		entry.key = UUID.randomUUID().toString();
		entry.type = "mob";
		entry.killer = id(killer);
		entry.name = customName == null ? "?" : customName.getString();
		entry.mobType = EntityType.getKey(mob.getType()).toString();
		entry.dim = mob.level().dimension().identifier().toString();
		entry.x = mob.getX();
		entry.y = mob.getY();
		entry.z = mob.getZ();
		entry.yRot = mob.getYRot();
		entry.xRot = mob.getXRot();
		registerCapture(killer, entry);
		spawnRealmMob(entry);
	}

	private static void registerCapture(ServerPlayer killer, RealmState.Entry entry) {
		String killerId = id(killer);
		RealmState.Owner owner = state.owner(killerId);
		owner.name = killer.getName().getString();
		boolean first = state.countOf(killerId) == 0;
		state.data.entries.put(entry.key, entry);
		SoulManager.addKarma(killer, "player".equals(entry.type) ? -8 : -3);
		if (first) {
			owner.soulTaken = true;
			chat(killer, "Der Preis ist bezahlt: Deine Seele wurde genommen, solange jemand im Schatten gefangen ist. "
					+ "Wird die Schattenwelt leer, stirbst du.");
		}
		state.save();
	}

	// ================================================================== Schattenwelt-Mobs

	private static void spawnRealmMob(RealmState.Entry entry) {
		ServerLevel realm = server.getLevel(ShadowRealm.REALM);
		Optional<EntityType<?>> type = mobTypeOf(entry.mobType);
		if (realm == null || type.isEmpty()) {
			return;
		}
		Entity entity = type.get().create(realm, EntitySpawnReason.TRIGGERED);
		if (entity == null) {
			return;
		}
		ThreadLocalRandom random = ThreadLocalRandom.current();
		double mx = REALM_X + random.nextDouble(-6.0, 6.0);
		double mz = REALM_Z + random.nextDouble(-6.0, 6.0);
		double my = realm.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, (int) Math.floor(mx), (int) Math.floor(mz));
		entity.setPos(mx, my, mz);
		entity.setCustomName(Component.literal(entry.name));
		entity.setCustomNameVisible(true);
		entity.addTag(MOB_TAG + entry.key);
		if (entity instanceof Mob mob) {
			mob.setPersistenceRequired();
		}
		realm.addFreshEntity(entity);
	}

	private static void spawnMobAt(RealmState.Entry entry) {
		ServerLevel level = levelOf(entry.dim);
		Optional<EntityType<?>> type = mobTypeOf(entry.mobType);
		if (type.isEmpty()) {
			return;
		}
		Entity entity = type.get().create(level, EntitySpawnReason.TRIGGERED);
		if (entity == null) {
			return;
		}
		entity.setPos(entry.x, entry.y, entry.z);
		entity.setYRot(entry.yRot);
		entity.setCustomName(Component.literal(entry.name));
		entity.setCustomNameVisible(true);
		if (entity instanceof Mob mob) {
			mob.setPersistenceRequired();
		}
		level.addFreshEntity(entity);
	}

	private static List<Entity> realmEntities() {
		ServerLevel realm = server.getLevel(ShadowRealm.REALM);
		if (realm == null) {
			return new ArrayList<>();
		}
		return new ArrayList<>(realm.getEntities((Entity) null, new AABB(-96, -8, -96, 96, 80, 96)));
	}

	private static void removeRealmMob(String key) {
		for (Entity entity : realmEntities()) {
			if (tagsOf(entity).contains(MOB_TAG + key)) {
				entity.discard();
			}
		}
	}

	/** Entfernt Schattenwelt-Mobs, deren Gefangenschaft nicht mehr existiert. */
	private static void cleanupRealmMobs() {
		for (Entity entity : realmEntities()) {
			String key = captiveKeyOf(entity);
			if (key != null && !state.data.entries.containsKey(key)) {
				entity.discard();
			}
		}
	}

	// ================================================================== Zurückrufen

	/** Gibt einen Gefangenen frei. Liefert den Spieler zurück, falls ein Spieler online zurückgeholt wurde. */
	private static ServerPlayer releaseEntry(RealmState.Entry entry) {
		state.data.entries.remove(entry.key);
		if (entry.armyOwner != null) {
			Army army = armies.get(parseUuid(entry.armyOwner));
			if (army != null) {
				army.keys.remove(entry.key);
				if (army.keys.isEmpty()) {
					armies.remove(parseUuid(entry.armyOwner));
				}
			}
		}

		if ("mob".equals(entry.type)) {
			removeRealmMob(entry.key);
			spawnMobAt(entry);
			state.save();
			return null;
		}

		ServerPlayer target = server.getPlayerList().getPlayer(parseUuid(entry.key));
		if (target != null) {
			bringBack(target, entry);
			state.save();
			return target;
		}
		state.data.pendingReturns.put(entry.key, entry);
		state.save();
		return null;
	}

	private static void recall(ServerPlayer wielder, String key) {
		RealmState.Entry entry = state.data.entries.get(key);
		if (entry == null || !canRecall(wielder, entry)) {
			return;
		}
		ServerPlayer recalled = releaseEntry(entry);
		SoulManager.addKarma(wielder, entry.orphan ? 10 : 6);
		tell(wielder, entry.name + " wurde zurückgerufen.");
		if (!entry.orphan) {
			afterRemoval(entry.killer, recalled, false);
		}
	}

	/** Eigene Gefangene und herrenlose darf jeder Klingenträger zurückrufen. */
	private static boolean canRecall(ServerPlayer wielder, RealmState.Entry entry) {
		return entry.orphan || id(wielder).equals(entry.killer);
	}

	private static void recallAll(ServerPlayer wielder) {
		String me = id(wielder);
		// Erst Mobs, dann Spieler: der zuletzt zurückgeholte Spieler bekommt die Seele.
		for (RealmState.Entry entry : state.entriesOf(me, "mob")) {
			releaseEntry(entry);
			if (!entry.orphan) {
				afterRemoval(me, null, false);
			}
		}
		for (RealmState.Entry entry : state.entriesOf(me, "player")) {
			ServerPlayer recalled = releaseEntry(entry);
			if (!entry.orphan) {
				afterRemoval(me, recalled, false);
			}
		}
	}

	/**
	 * Der Besitzer ist gestorben: Die Gefangenen bleiben in der Schattenwelt (herrenlos), bis jemand
	 * mit einer Klinge sie herausholt. Die Seele wird ausgezahlt und liegt in einer Kiste am Welt-Spawn.
	 */
	private static void orphanAll(String ownerId) {
		for (RealmState.Entry entry : state.ownEntries(ownerId)) {
			entry.orphan = true;
		}
		RealmState.Owner owner = state.data.owners.get(ownerId);
		if (owner != null && owner.soulTaken) {
			owner.soulTaken = false;
			ItemStack soul = ModItems.createSoul(owner.name == null ? "?" : owner.name);
			BlockPos chest = placeSoulChest(soul, ownerId, owner.name);
			server.getPlayerList().broadcastSystemMessage(Component.literal(
					owner.name + " ist gefallen. Die Seele liegt in einer Kiste am Welt-Spawn ("
							+ chest.getX() + " " + chest.getY() + " " + chest.getZ() + "). "
							+ "Seine Gefangenen bleiben im Schatten, bis jemand sie mit einer Klinge herausholt."), false);
		}
		state.save();
	}

	private static void bringBack(ServerPlayer target, RealmState.Entry entry) {
		if (entry.inArmy) {
			entry.inArmy = false;
		}
		// Falls er als Soldat zurückgerufen wird, verschwindet das Kit.
		target.getInventory().clearContent();
		ServerLevel destination = levelOf(entry.dim);
		target.setGameMode(GameType.byName(entry.gameMode, GameType.SURVIVAL));
		target.teleportTo(destination, entry.x, entry.y, entry.z, Set.of(), entry.yRot, entry.xRot, true);
		target.sendSystemMessage(Component.literal("Du wurdest aus der Schattenwelt zurückgerufen."));
		SoulManager.applyGhostIfNeeded(target);
	}

	static void sendToRealm(ServerPlayer player) {
		ServerLevel realm = server.getLevel(ShadowRealm.REALM);
		if (realm == null) {
			player.sendSystemMessage(Component.literal("Die Schattenwelt konnte nicht geladen werden."));
			return;
		}
		player.getInventory().clearContent();
		player.setGameMode(GameType.ADVENTURE);
		if (!inRealmDim(player)) {
			player.teleportTo(realm, REALM_X, REALM_Y, REALM_Z, Set.of(), 0.0F, 0.0F, true);
			player.sendSystemMessage(Component.literal("Du wurdest von der Schattenklinge in die Schattenwelt verbannt..."));
		}
	}

	// ================================================================== Seele

	/**
	 * Wird nach jedem Entfernen eines Gefangenen aufgerufen. Ist die Schattenwelt des Besitzers leer,
	 * wird die Seele ausgezahlt und der Besitzer stirbt.
	 */
	private static void afterRemoval(String ownerId, ServerPlayer lastRecalled, boolean ownerAlreadyDead) {
		if (state.countOf(ownerId) > 0) {
			return;
		}
		RealmState.Owner owner = state.data.owners.get(ownerId);
		if (owner == null || !owner.soulTaken) {
			return;
		}
		owner.soulTaken = false;
		state.save();

		ItemStack soul = ModItems.createSoul(owner.name == null ? "?" : owner.name);
		boolean given = lastRecalled != null && lastRecalled.getInventory().add(soul);
		if (given) {
			server.getPlayerList().broadcastSystemMessage(Component.literal(
					lastRecalled.getName().getString() + " hat die Seele von " + owner.name + " erhalten."), false);
		} else {
			BlockPos chest = placeSoulChest(soul, ownerId, owner.name);
			server.getPlayerList().broadcastSystemMessage(Component.literal(
					"Die Seele von " + owner.name + " liegt in einer Kiste am Welt-Spawn ("
							+ chest.getX() + " " + chest.getY() + " " + chest.getZ() + ")."), false);
		}

		if (!ownerAlreadyDead) {
			killOwner(ownerId);
		}
	}

	private static void killOwner(String ownerId) {
		ServerPlayer player = server.getPlayerList().getPlayer(parseUuid(ownerId));
		if (player == null) {
			state.owner(ownerId).pendingDeath = true;
			state.save();
			return;
		}
		killNow(player);
	}

	private static void killNow(ServerPlayer player) {
		ServerLevel level = server.getLevel(player.level().dimension());
		if (level == null) {
			return;
		}
		chat(player, "Die Schattenwelt ist leer. Dein Preis ist fällig: Du verlierst deine Seele.");
		SoulManager.loseSoul(player);
		player.kill(level);
	}

	private static BlockPos placeSoulChest(ItemStack stack, String ownerId, String ownerName) {
		ServerLevel level = server.overworld();
		BlockPos spawn = worldSpawn(level);
		for (int i = 0; i < 16; i++) {
			int x = spawn.getX() + i;
			int z = spawn.getZ();
			int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
			BlockPos pos = new BlockPos(x, y, z);
			if (level.getBlockState(pos).isAir()) {
				level.setBlockAndUpdate(pos, Blocks.CHEST.defaultBlockState());
				BlockEntity blockEntity = level.getBlockEntity(pos);
				if (blockEntity instanceof ChestBlockEntity chest) {
					chest.setItem(0, stack);
					SoulManager.registerChest(pos, ownerId, ownerName);
					return pos;
				}
			}
		}
		Block.popResource(level, spawn, stack);
		return spawn;
	}

	/** Welt-Spawn des Overworlds. Per Reflection, damit es mit verschiedenen Versionsnamen klappt. */
	static BlockPos worldSpawn(ServerLevel level) {
		Object[] targets = {level, level.getLevelData()};
		String[] methods = {"getRespawnData", "getSharedSpawnPos"};
		for (Object target : targets) {
			for (String name : methods) {
				try {
					Object result = target.getClass().getMethod(name).invoke(target);
					if (result instanceof BlockPos pos) {
						return pos;
					}
					if (result != null) {
						Object pos = result.getClass().getMethod("pos").invoke(result);
						if (pos instanceof BlockPos blockPos) {
							return blockPos;
						}
					}
				} catch (ReflectiveOperationException | RuntimeException ignored) {
				}
			}
		}
		return new BlockPos(0, 64, 0);
	}

	// ================================================================== Menü-Aktionen

	/** Aktion vom Client (Taste R / Menü). */
	public static void handleAction(ServerPlayer wielder, String action) {
		if (state == null || server == null || !wielder.isAlive()) {
			return;
		}
		if (action.startsWith("elixir_")) {
			SoulManager.handleElixir(wielder, action);
			return;
		}
		if (!isBlade(wielder)) {
			return;
		}

		boolean refreshMenu = true;
		if (action.equals("open")) {
			// nur Menü schicken
		} else if (action.startsWith("recall:")) {
			recall(wielder, action.substring("recall:".length()));
		} else if (action.equals("recallall")) {
			recallAll(wielder);
		} else if (action.equals("enter")) {
			enterShadow(wielder);
		} else if (action.equals("leave")) {
			leaveShadow(wielder);
		} else if (action.equals("army_arm")) {
			armArmy(wielder);
		} else if (action.equals("army_dismiss")) {
			dismissArmy(wielder.getUUID());
			tell(wielder, "Die Armee wurde zurückgeschickt.");
		} else if (action.equals("army_key")) {
			armyKey(wielder);
			refreshMenu = false;
		} else {
			return;
		}

		if (refreshMenu && wielder.isAlive()) {
			sendMenu(wielder);
		}
	}

	private static void sendMenu(ServerPlayer player) {
		String me = id(player);
		MenuData data = new MenuData();
		for (RealmState.Entry entry : state.entriesOf(me, "player")) {
			data.players.add(item(entry, "player"));
		}
		for (RealmState.Entry entry : state.entriesOf(me, "mob")) {
			String type = entry.mobType == null ? "mob" : entry.mobType;
			int colon = type.indexOf(':');
			data.mobs.add(item(entry, colon >= 0 ? type.substring(colon + 1) : type));
		}
		data.ownCount = state.countOf(me);
		RealmState.Owner owner = state.data.owners.get(me);
		data.soulTaken = owner != null && owner.soulTaken;
		data.inRealm = inRealmDim(player);
		data.armed = armed.contains(player.getUUID());
		data.armyActive = armies.containsKey(player.getUUID());
		ServerPlayNetworking.send(player, new MenuPayload(data.toJson()));
	}

	private static MenuData.Item item(RealmState.Entry entry, String type) {
		MenuData.Item item = new MenuData.Item();
		item.key = entry.key;
		item.name = entry.name;
		item.type = type;
		item.orphan = entry.orphan;
		return item;
	}

	private static void enterShadow(ServerPlayer player) {
		if (inRealmDim(player)) {
			tell(player, "Du bist bereits im Schatten.");
			return;
		}
		ServerLevel realm = server.getLevel(ShadowRealm.REALM);
		if (realm == null) {
			tell(player, "Die Schattenwelt konnte nicht geladen werden.");
			return;
		}
		dismissArmy(player.getUUID());
		armed.remove(player.getUUID());

		RealmState.Owner owner = state.owner(id(player));
		owner.name = player.getName().getString();
		owner.inRealm = true;
		owner.retDim = player.level().dimension().identifier().toString();
		owner.retX = player.getX();
		owner.retY = player.getY();
		owner.retZ = player.getZ();
		owner.retYRot = player.getYRot();
		owner.retXRot = player.getXRot();
		state.save();

		player.teleportTo(realm, REALM_X, REALM_Y, REALM_Z, Set.of(), 0.0F, 0.0F, true);
		tell(player, "Du betrittst den Schatten.");
	}

	private static void leaveShadow(ServerPlayer player) {
		if (!inRealmDim(player)) {
			return;
		}
		RealmState.Entry captive = state.data.entries.get(id(player));
		if (captive != null) {
			return; // Gefangene kommen nicht selbst heraus
		}
		RealmState.Owner owner = state.owner(id(player));
		owner.inRealm = false;
		state.save();

		if (owner.retDim != null) {
			ServerLevel level = levelOf(owner.retDim);
			player.teleportTo(level, owner.retX, owner.retY, owner.retZ, Set.of(), owner.retYRot, owner.retXRot, true);
		} else {
			ServerLevel overworld = server.overworld();
			BlockPos spawn = worldSpawn(overworld);
			player.teleportTo(overworld, spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5, Set.of(), 0.0F, 0.0F, true);
		}
		tell(player, "Du verlässt den Schatten.");
	}

	// ================================================================== Armee

	private static List<RealmState.Entry> onlineCaptivePlayers(String ownerId) {
		List<RealmState.Entry> list = new ArrayList<>();
		for (RealmState.Entry entry : state.entriesOf(ownerId, "player")) {
			if (server.getPlayerList().getPlayer(parseUuid(entry.key)) != null) {
				list.add(entry);
			}
		}
		return list;
	}

	private static void armArmy(ServerPlayer player) {
		if (armies.containsKey(player.getUUID())) {
			tell(player, "Deine Armee ist bereits unterwegs.");
			return;
		}
		if (inRealmDim(player)) {
			tell(player, "Die Armee kann nur in der normalen Welt gerufen werden.");
			return;
		}
		if (onlineCaptivePlayers(id(player)).isEmpty()) {
			tell(player, "Keiner deiner Gefangenen ist online.");
			return;
		}
		armed.add(player.getUUID());
		tell(player, "Armee geladen! Drücke Shift + R, um sie zu rufen.");
	}

	/** Shift + R: Armee rufen (wenn geladen) oder zurückschicken (wenn aktiv). */
	private static void armyKey(ServerPlayer player) {
		UUID uuid = player.getUUID();
		if (armies.containsKey(uuid)) {
			dismissArmy(uuid);
			tell(player, "Die Armee wurde zurückgeschickt.");
			return;
		}
		if (!armed.contains(uuid)) {
			tell(player, "Lade die Armee zuerst im Menü (R).");
			return;
		}
		if (inRealmDim(player)) {
			tell(player, "Die Armee kann nur in der normalen Welt gerufen werden.");
			return;
		}
		List<RealmState.Entry> soldiers = onlineCaptivePlayers(id(player));
		if (soldiers.isEmpty()) {
			armed.remove(uuid);
			tell(player, "Keiner deiner Gefangenen ist online.");
			return;
		}

		Army army = new Army();
		army.nextDrain = server.getTickCount() + ARMY_GRACE_TICKS;
		int index = 0;
		for (RealmState.Entry entry : soldiers) {
			ServerPlayer soldier = server.getPlayerList().getPlayer(parseUuid(entry.key));
			if (soldier == null) {
				continue;
			}
			entry.inArmy = true;
			entry.armyOwner = id(player);
			army.keys.add(entry.key);
			giveKit(soldier);
			soldier.setGameMode(GameType.ADVENTURE);
			teleportNear(soldier, player, index++);
			soldier.sendSystemMessage(Component.literal(
					"Du wurdest als Armee gerufen. Du kannst nur das Ziel von " + player.getName().getString() + " angreifen."));
		}
		armies.put(uuid, army);
		armed.remove(uuid);
		state.save();
		tell(player, "Die Armee ist da! Nach 30 Sekunden kostet sie dich Herzen. Shift + R schickt sie zurück.");
	}

	private static void giveKit(ServerPlayer soldier) {
		soldier.setItemSlot(EquipmentSlot.HEAD, kit(Items.NETHERITE_HELMET));
		soldier.setItemSlot(EquipmentSlot.CHEST, kit(Items.NETHERITE_CHESTPLATE));
		soldier.setItemSlot(EquipmentSlot.LEGS, kit(Items.NETHERITE_LEGGINGS));
		soldier.setItemSlot(EquipmentSlot.FEET, kit(Items.NETHERITE_BOOTS));
		soldier.setItemSlot(EquipmentSlot.MAINHAND, kit(Items.NETHERITE_SWORD));
	}

	private static ItemStack kit(net.minecraft.world.item.Item item) {
		ItemStack stack = new ItemStack(item);
		stack.set(DataComponents.CUSTOM_NAME, Component.literal(KIT_NAME));
		return stack;
	}

	private static void teleportNear(ServerPlayer soldier, ServerPlayer owner, int index) {
		ServerLevel level = server.getLevel(owner.level().dimension());
		if (level == null) {
			return;
		}
		double angle = index * (Math.PI / 3.0);
		soldier.teleportTo(level,
				owner.getX() + Math.cos(angle) * 2.5,
				owner.getY(),
				owner.getZ() + Math.sin(angle) * 2.5,
				Set.of(), owner.getYRot(), 0.0F, true);
	}

	private static void dismissArmy(UUID ownerUuid) {
		Army army = armies.remove(ownerUuid);
		if (army == null) {
			return;
		}
		for (String key : new ArrayList<>(army.keys)) {
			RealmState.Entry entry = state.data.entries.get(key);
			if (entry == null) {
				continue;
			}
			entry.inArmy = false;
			entry.armyOwner = null;
			ServerPlayer soldier = server.getPlayerList().getPlayer(parseUuid(key));
			if (soldier != null) {
				sendToRealm(soldier);
			}
		}
		state.save();
	}

	private static void tickArmies(long tick) {
		if (armies.isEmpty()) {
			return;
		}
		List<UUID> toDismiss = new ArrayList<>();
		for (Map.Entry<UUID, Army> mapEntry : armies.entrySet()) {
			UUID ownerUuid = mapEntry.getKey();
			Army army = mapEntry.getValue();
			ServerPlayer owner = server.getPlayerList().getPlayer(ownerUuid);
			if (owner == null || !owner.isAlive() || inRealmDim(owner)) {
				toDismiss.add(ownerUuid);
				continue;
			}

			int index = 0;
			for (String key : army.keys) {
				ServerPlayer soldier = server.getPlayerList().getPlayer(parseUuid(key));
				if (soldier == null || !soldier.isAlive()) {
					continue;
				}
				boolean otherDimension = !soldier.level().dimension().equals(owner.level().dimension());
				if (otherDimension || soldier.distanceTo(owner) > ARMY_LEASH) {
					teleportNear(soldier, owner, index);
				}
				index++;
				removeDroppedKit(soldier);
			}

			if (tick >= army.nextDrain) {
				army.nextDrain += ARMY_DRAIN_INTERVAL;
				owner.setHealth(Math.max(1.0F, owner.getHealth() - 2.0F));
				tell(owner, "Die Armee zehrt an deiner Lebenskraft (-1 Herz).");
			}
		}
		for (UUID ownerUuid : toDismiss) {
			dismissArmy(ownerUuid);
		}
	}

	/** Das Kit ist nur geliehen: weggeworfene Teile verschwinden sofort. */
	private static void removeDroppedKit(ServerPlayer soldier) {
		ServerLevel level = server.getLevel(soldier.level().dimension());
		if (level == null) {
			return;
		}
		for (ItemEntity dropped : level.getEntitiesOfClass(ItemEntity.class, soldier.getBoundingBox().inflate(8.0))) {
			if (isKit(dropped.getItem())) {
				dropped.discard();
			}
		}
	}
}
