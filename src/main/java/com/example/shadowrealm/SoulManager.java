package com.example.shadowrealm;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Seelen-System:
 * - jeder Spieler hat eine Seele mit Rang (Karma)
 * - ohne Seele ist man ein Geist (unsichtbar, Abenteuermodus, kann nichts tun)
 * - wer seine Seele verliert, hinterlässt eine Leiche (liegender Spieler) mit seiner Beute
 * - Ritual: Seele in der Hand + Schleichen an der Leiche = der Besitzer der Seele lebt an der Leiche wieder
 * - Seelen-Kiste am Welt-Spawn mit Regeln
 * - Elixier-Schwert: zieht Seelen böser Spieler, Darkness und Bund
 */
public final class SoulManager {
	private static final String CORPSE_TAG = "shadowcorpse:";
	private static final String SOUL_PREFIX = "Seele von ";

	/** Ab diesem Karma gilt eine Seele als böse (Rang "Böse" oder schlimmer). */
	static final int EVIL = -20;
	static final int VERY_EVIL = -60;

	private static final int RITUAL_TICKS = 40;
	private static final int ELIXIR_MIN_SOULS = 3;
	private static final long ABILITY_COOLDOWN = 3600; // 3 Minuten
	private static final long DARKNESS_TICKS = 1200; // 1 Minute
	private static final long BOND_TICKS = 2400; // 2 Minuten
	private static final long HIT_MEMORY = 600; // 30 Sekunden
	private static final double BOND_FREE_DISTANCE = 6.0;
	private static final String OVERWORLD = "minecraft:overworld";

	private static final Map<UUID, Integer> ritual = new HashMap<>();
	private static final Map<UUID, String> lastSent = new HashMap<>();
	private static final Map<UUID, Hit> hits = new HashMap<>();
	private static final Map<UUID, Long> darkReady = new HashMap<>();
	private static final Map<UUID, Long> bondReady = new HashMap<>();
	private static final Map<UUID, Bond> bonds = new HashMap<>();

	private record Hit(UUID victim, long tick) {
	}

	private record Bond(UUID target, long until) {
	}

	private SoulManager() {
	}

	private static RealmState state() {
		return RealmManager.state();
	}

	private static MinecraftServer server() {
		return RealmManager.server();
	}

	private static String id(ServerPlayer player) {
		return player.getUUID().toString();
	}

	// ================================================================== Setup

	public static void register() {
		ServerLivingEntityEvents.AFTER_DEATH.register(SoulManager::onDeath);
		ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> allowDamage(entity, source));

		ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
			if (!alive && state() != null) {
				onJoinOrRespawn(newPlayer);
			}
		});
		ServerPlayConnectionEvents.JOIN.register((handler, sender, s) -> {
			if (state() != null) {
				onJoinOrRespawn(handler.getPlayer());
			}
		});
		ServerPlayConnectionEvents.DISCONNECT.register((handler, s) -> {
			UUID uuid = handler.getPlayer().getUUID();
			ritual.remove(uuid);
			lastSent.remove(uuid);
			bonds.remove(uuid);
		});

		net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STARTED.register(s -> {
			ritual.clear();
			lastSent.clear();
			hits.clear();
			bonds.clear();
			if (state() != null) {
				for (RealmState.Corpse corpse : state().data.corpses.values()) {
					forceChunk(corpse, true);
				}
			}
		});

		registerCommands();
	}

	private static void registerCommands() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
				dispatcher.register(Commands.literal("shadowrealm")
						.requires(source -> source.permissions().hasPermission(Permissions.COMMANDS_MODERATOR))
						.then(Commands.literal("karma")
								.then(Commands.argument("player", EntityArgument.player())
										.then(Commands.argument("value", IntegerArgumentType.integer(-100, 100))
												.executes(ctx -> {
													ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
													int value = IntegerArgumentType.getInteger(ctx, "value");
													setKarma(target, value);
													return 1;
												}))))
						.then(Commands.literal("losesoul")
								.then(Commands.argument("player", EntityArgument.player())
										.executes(ctx -> {
											ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
											loseSoul(target);
											return 1;
										})))
						.then(Commands.literal("givesoul")
								.then(Commands.argument("player", EntityArgument.player())
										.executes(ctx -> {
											ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
											restoreSoulInPlace(target);
											return 1;
										})))));
	}

	// ================================================================== Seele und Rang

	static RealmState.SoulInfo info(ServerPlayer player) {
		RealmState.SoulInfo info = state().data.souls.computeIfAbsent(id(player), k -> new RealmState.SoulInfo());
		info.name = player.getName().getString();
		return info;
	}

	static boolean isGhost(ServerPlayer player) {
		RealmState.SoulInfo info = state().data.souls.get(id(player));
		return info != null && !info.hasSoul;
	}

	/** 0 = Verderbt, 1 = Böse, 2 = Neutral, 3 = Gut, 4 = Rein */
	static int rankIndex(int karma) {
		if (karma >= 60) {
			return 4;
		}
		if (karma >= 20) {
			return 3;
		}
		if (karma > EVIL) {
			return 2;
		}
		if (karma > VERY_EVIL) {
			return 1;
		}
		return 0;
	}

	private static final String[] RANK_NAMES = {"Verderbt", "Böse", "Neutral", "Gut", "Rein"};

	static String rankName(int karma) {
		return RANK_NAMES[rankIndex(karma)];
	}

	static void addKarma(ServerPlayer player, int delta) {
		if (state() == null) {
			return;
		}
		RealmState.SoulInfo info = info(player);
		int before = rankIndex(info.karma);
		info.karma = Math.max(-100, Math.min(100, info.karma + delta));
		if (rankIndex(info.karma) != before) {
			RealmManager.tell(player, "Dein Seelen-Rang ist jetzt: " + rankName(info.karma));
		}
		state().save();
	}

	private static void setKarma(ServerPlayer player, int value) {
		RealmState.SoulInfo info = info(player);
		info.karma = value;
		state().save();
		RealmManager.tell(player, "Dein Seelen-Rang ist jetzt: " + rankName(value) + " (" + value + ")");
	}

	// ================================================================== Items der Seele

	private static boolean isSoulItem(ItemStack stack) {
		return stack.is(ModItems.SOUL);
	}

	private static String soulOwnerName(ItemStack stack) {
		Component name = stack.get(DataComponents.CUSTOM_NAME);
		if (name == null) {
			return null;
		}
		String text = name.getString();
		return text.startsWith(SOUL_PREFIX) ? text.substring(SOUL_PREFIX.length()) : null;
	}

	private static Map.Entry<String, RealmState.SoulInfo> findSoul(String name) {
		if (name == null) {
			return null;
		}
		for (Map.Entry<String, RealmState.SoulInfo> e : state().data.souls.entrySet()) {
			if (name.equals(e.getValue().name)) {
				return e;
			}
		}
		return null;
	}

	private static int countSoulItems(ServerPlayer player, String ownerName) {
		int n = 0;
		for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
			ItemStack s = player.getInventory().getItem(i);
			if (isSoulItem(s) && ownerName.equals(soulOwnerName(s))) {
				n += s.getCount();
			}
		}
		return n;
	}

	private static void removeSoulItems(ServerPlayer player, String ownerName) {
		for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
			ItemStack s = player.getInventory().getItem(i);
			if (isSoulItem(s) && ownerName.equals(soulOwnerName(s))) {
				player.getInventory().setItem(i, ItemStack.EMPTY);
			}
		}
	}

	// ================================================================== Schaden und Tod

	private static boolean allowDamage(LivingEntity entity, DamageSource source) {
		if (state() == null || server() == null) {
			return true;
		}
		if (entity instanceof ServerPlayer victim && isGhost(victim)) {
			return false;
		}
		Entity attacker = source.getEntity();
		if (attacker instanceof ServerPlayer player) {
			if (isGhost(player)) {
				return false;
			}
			if (player.getMainHandItem().getItem() instanceof ElixirSwordItem && entity instanceof ServerPlayer victim) {
				hits.put(player.getUUID(), new Hit(victim.getUUID(), server().getTickCount()));
			}
		}
		return true;
	}

	private static void onDeath(LivingEntity entity, DamageSource source) {
		if (state() == null || server() == null) {
			return;
		}
		ServerPlayer killer = source.getEntity() instanceof ServerPlayer k ? k : null;
		if (entity instanceof ServerPlayer victim) {
			if (killer != null && killer != victim) {
				RealmState.SoulInfo victimInfo = info(victim);
				boolean evil = victimInfo.karma <= EVIL;
				addKarma(killer, evil ? 8 : -15);
				if (evil && victimInfo.hasSoul
						&& killer.getMainHandItem().getItem() instanceof ElixirSwordItem) {
					extractSoul(killer, victim);
				}
			}
		} else if (killer != null) {
			MobCategory category = entity.getType().getCategory();
			if (category == MobCategory.MONSTER) {
				addKarma(killer, 1);
			} else if (category == MobCategory.CREATURE) {
				addKarma(killer, -1);
			}
		}
	}

	// ================================================================== Seele verlieren, Leiche

	/** Preis fällig: Beute des Spielers wandert zur Leiche, er wird zum Geist (vor dem Töten aufrufen). */
	static void loseSoul(ServerPlayer player) {
		if (state() == null) {
			return;
		}
		RealmState.SoulInfo info = info(player);
		if (!info.hasSoul) {
			return;
		}
		info.hasSoul = false;
		player.getInventory().dropAll();
		createCorpse(player);
		if (player.isAlive()) {
			applyGhost(player);
		}
		state().save();
	}

	/** Spieler ist schon gestorben (Beute liegt am Boden): Seele verloren, Leiche mit Beute. */
	static void loseSoulAfterDeath(ServerPlayer player) {
		if (state() == null) {
			return;
		}
		RealmState.SoulInfo info = info(player);
		if (!info.hasSoul) {
			return;
		}
		info.hasSoul = false;
		createCorpse(player);
		state().save();
	}

	private static void extractSoul(ServerPlayer killer, ServerPlayer victim) {
		RealmState.SoulInfo victimInfo = info(victim);
		victimInfo.hasSoul = false;
		createCorpse(victim);

		RealmState.SoulInfo killerInfo = info(killer);
		killerInfo.stolen++;
		if (victimInfo.karma <= VERY_EVIL) {
			killerInfo.evilSoul = true;
		}
		ItemStack soul = ModItems.createSoul(victim.getName().getString());
		if (!killer.getInventory().add(soul)) {
			killer.drop(soul, false);
		}
		addKarma(killer, -10);
		server().getPlayerList().broadcastSystemMessage(Component.literal(
				killer.getName().getString() + " hat die Seele von " + victim.getName().getString()
						+ " (" + rankName(victimInfo.karma) + ") herausgezogen!"), false);
		state().save();
	}

	private static void createCorpse(ServerPlayer victim) {
		ServerLevel level = server().getLevel(victim.level().dimension());
		if (level == null) {
			return;
		}
		RealmState.Corpse corpse = new RealmState.Corpse();
		corpse.id = UUID.randomUUID().toString().substring(0, 8);
		corpse.victim = id(victim);
		corpse.name = victim.getName().getString();
		corpse.dim = level.dimension().identifier().toString();
		corpse.x = victim.getX();
		corpse.y = victim.getY();
		corpse.z = victim.getZ();
		state().data.corpses.put(corpse.id, corpse);

		// Beute: alle frisch gedroppten Items in der Nähe werden an die Leiche gebunden.
		AABB box = new AABB(corpse.x - 6, corpse.y - 4, corpse.z - 6, corpse.x + 6, corpse.y + 4, corpse.z + 6);
		for (ItemEntity item : level.getEntitiesOfClass(ItemEntity.class, box)) {
			if (item.tickCount <= 3 && !hasCorpseTag(item)) {
				item.addTag(CORPSE_TAG + corpse.id);
				item.setUnlimitedLifetime();
				item.setNeverPickUp();
			}
		}

		forceChunk(corpse, true);
		try {
			String command = String.format(Locale.ROOT,
					"execute in %s run summon minecraft:mannequin %.2f %.2f %.2f "
							+ "{Tags:[\"%s%s\"],pose:\"sleeping\",profile:\"%s\",immovable:1b,Invulnerable:1b,"
							+ "hide_description:1b,CustomName:\"Leiche von %s\",CustomNameVisible:1b}",
					corpse.dim, corpse.x, corpse.y, corpse.z, CORPSE_TAG, corpse.id, corpse.name, corpse.name);
			server().getCommands().performPrefixedCommand(
					server().createCommandSourceStack().withSuppressedOutput(), command);
		} catch (RuntimeException e) {
			System.err.println("[shadowrealm] Konnte Leiche nicht erzeugen: " + e);
		}
		server().getPlayerList().broadcastSystemMessage(Component.literal(
				corpse.name + " hat seine Seele verloren. Seine Leiche liegt bei "
						+ (int) corpse.x + " " + (int) corpse.y + " " + (int) corpse.z + "."), false);
	}

	private static boolean hasCorpseTag(Entity entity) {
		for (String tag : RealmManager.tagsOf(entity)) {
			if (tag.startsWith(CORPSE_TAG)) {
				return true;
			}
		}
		return false;
	}

	private static void forceChunk(RealmState.Corpse corpse, boolean force) {
		ServerLevel level = RealmManager.levelOf(corpse.dim);
		int cx = ((int) Math.floor(corpse.x)) >> 4;
		int cz = ((int) Math.floor(corpse.z)) >> 4;
		if (!force) {
			// nicht freigeben, solange eine andere Leiche im selben Chunk liegt
			for (RealmState.Corpse other : state().data.corpses.values()) {
				if (other != corpse && other.dim.equals(corpse.dim)
						&& (((int) Math.floor(other.x)) >> 4) == cx && (((int) Math.floor(other.z)) >> 4) == cz) {
					return;
				}
			}
		}
		try {
			level.setChunkForced(cx, cz, force);
		} catch (RuntimeException e) {
			System.err.println("[shadowrealm] Chunk-Fehler: " + e);
		}
	}

	/** Entfernt die Leiche samt Körper und gibt ihre Beute zurück. */
	private static List<ItemStack> takeCorpse(RealmState.Corpse corpse) {
		List<ItemStack> loot = new ArrayList<>();
		state().data.corpses.remove(corpse.id);
		ServerLevel level = RealmManager.levelOf(corpse.dim);
		AABB box = new AABB(corpse.x - 16, corpse.y - 16, corpse.z - 16, corpse.x + 16, corpse.y + 16, corpse.z + 16);
		String tag = CORPSE_TAG + corpse.id;
		for (Entity entity : new ArrayList<>(level.getEntities((Entity) null, box))) {
			if (!RealmManager.tagsOf(entity).contains(tag)) {
				continue;
			}
			if (entity instanceof ItemEntity item) {
				loot.add(item.getItem().copy());
			}
			entity.discard();
		}
		forceChunk(corpse, false);
		state().save();
		return loot;
	}

	private static RealmState.Corpse corpseOf(String victimId) {
		for (RealmState.Corpse corpse : state().data.corpses.values()) {
			if (victimId.equals(corpse.victim)) {
				return corpse;
			}
		}
		return null;
	}

	private static List<ItemStack> takeCorpseOf(String victimId) {
		RealmState.Corpse corpse = corpseOf(victimId);
		return corpse == null ? new ArrayList<>() : takeCorpse(corpse);
	}

	// ================================================================== Geist

	private static void applyGhost(ServerPlayer player) {
		player.setGameMode(GameType.ADVENTURE);
		player.addEffect(new MobEffectInstance(MobEffects.INVISIBILITY, MobEffectInstance.INFINITE_DURATION, 0, false, false));
		for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
			ItemStack stack = player.getInventory().getItem(i);
			if (!stack.isEmpty() && !isSoulItem(stack)) {
				player.getInventory().setItem(i, ItemStack.EMPTY);
			}
		}
	}

	static void applyGhostIfNeeded(ServerPlayer player) {
		if (state() != null && isGhost(player)) {
			applyGhost(player);
		}
	}

	private static void liftGhost(ServerPlayer player) {
		player.removeEffect(MobEffects.INVISIBILITY);
		boolean captive = state().data.entries.containsKey(id(player));
		player.setGameMode(captive ? GameType.ADVENTURE : GameType.SURVIVAL);
	}

	/** Ohne Seele zurück ins Leben (Test-Befehl): am Ort. */
	private static void restoreSoulInPlace(ServerPlayer player) {
		RealmState.SoulInfo info = info(player);
		info.hasSoul = true;
		liftGhost(player);
		state().save();
		RealmManager.tell(player, "Du hast deine Seele zurück.");
	}

	static void onJoinOrRespawn(ServerPlayer player) {
		RealmState.SoulInfo info = info(player);
		RealmState.Revive revive = state().data.pendingRevives.remove(id(player));
		if (revive != null) {
			info.hasSoul = true;
			liftGhost(player);
			ServerLevel level = RealmManager.levelOf(revive.dim);
			player.teleportTo(level, revive.x, revive.y, revive.z, Set.of(), player.getYRot(), 0.0F, true);
			state().save();
		} else if (!info.hasSoul) {
			applyGhost(player);
		}
		sync(player, true);
	}

	/** Belebt einen Spieler an einem Ort wieder, mit Beute. Offline: beim nächsten Login. */
	private static void reviveAt(String uuid, String dim, double x, double y, double z, List<ItemStack> loot) {
		RealmState.SoulInfo info = state().data.souls.computeIfAbsent(uuid, k -> new RealmState.SoulInfo());
		info.hasSoul = true;
		ServerPlayer player = server().getPlayerList().getPlayer(RealmManager.parseUuid(uuid));
		if (player == null) {
			RealmState.Revive revive = new RealmState.Revive();
			revive.dim = dim;
			revive.x = x;
			revive.y = y;
			revive.z = z;
			state().data.pendingRevives.put(uuid, revive);
			ServerLevel level = RealmManager.levelOf(dim);
			for (ItemStack stack : loot) {
				Block.popResource(level, BlockPos.containing(x, y, z), stack);
			}
			state().save();
			return;
		}
		liftGhost(player);
		ServerLevel level = RealmManager.levelOf(dim);
		player.teleportTo(level, x, y, z, Set.of(), player.getYRot(), 0.0F, true);
		for (ItemStack stack : loot) {
			if (!player.getInventory().add(stack)) {
				player.drop(stack, false);
			}
		}
		RealmManager.chat(player, "Du lebst wieder! Deine Seele ist zurück.");
		state().save();
	}

	// ================================================================== Ritual

	private static RealmState.Corpse nearestCorpse(ServerPlayer player, double range) {
		RealmState.Corpse best = null;
		double bestDist = range * range;
		String dim = player.level().dimension().identifier().toString();
		for (RealmState.Corpse corpse : state().data.corpses.values()) {
			if (!corpse.dim.equals(dim)) {
				continue;
			}
			double dx = corpse.x - player.getX();
			double dy = corpse.y - player.getY();
			double dz = corpse.z - player.getZ();
			double dist = dx * dx + dy * dy + dz * dz;
			if (dist <= bestDist) {
				bestDist = dist;
				best = corpse;
			}
		}
		return best;
	}

	private static void tickRitual() {
		for (ServerPlayer player : server().getPlayerList().getPlayers()) {
			UUID uuid = player.getUUID();
			ItemStack held = player.getMainHandItem();
			if (!player.isShiftKeyDown() || !isSoulItem(held)) {
				ritual.remove(uuid);
				continue;
			}
			RealmState.Corpse corpse = nearestCorpse(player, 2.5);
			if (corpse == null) {
				ritual.remove(uuid);
				continue;
			}
			int progress = ritual.merge(uuid, 5, Integer::sum);
			if (progress < RITUAL_TICKS) {
				RealmManager.tell(player, "Ritual... " + (progress * 100 / RITUAL_TICKS) + "%");
				continue;
			}
			ritual.remove(uuid);
			performRitual(player, held, corpse);
		}
	}

	private static void performRitual(ServerPlayer player, ItemStack soul, RealmState.Corpse corpse) {
		Map.Entry<String, RealmState.SoulInfo> owner = findSoul(soulOwnerName(soul));
		if (owner == null) {
			RealmManager.tell(player, "Diese Seele gehört niemandem.");
			return;
		}
		if (owner.getValue().hasSoul) {
			RealmManager.tell(player, "Diese Seele gehört einem Lebenden.");
			return;
		}
		String ownerName = owner.getValue().name;
		soul.shrink(1);
		List<ItemStack> loot = takeCorpse(corpse);
		reviveAt(owner.getKey(), corpse.dim, corpse.x, corpse.y, corpse.z, loot);
		addKarma(player, 5);
		String text = ownerName.equals(corpse.name)
				? ownerName + " wurde durch das Ritual wiederbelebt!"
				: ownerName + " lebt wieder - an der Stelle und mit dem Besitz von " + corpse.name + "!";
		server().getPlayerList().broadcastSystemMessage(Component.literal(text), false);
	}

	// ================================================================== Seelen-Kiste

	static void registerChest(BlockPos pos, String ownerId, String ownerName) {
		RealmState.SoulChest chest = new RealmState.SoulChest();
		chest.x = pos.getX();
		chest.y = pos.getY();
		chest.z = pos.getZ();
		chest.owner = ownerId;
		chest.ownerName = ownerName == null ? "?" : ownerName;
		state().data.soulChests.add(chest);
		state().save();
	}

	private static boolean chestHasSoul(ChestBlockEntity chest, String ownerName) {
		for (int i = 0; i < chest.getContainerSize(); i++) {
			ItemStack stack = chest.getItem(i);
			if (isSoulItem(stack) && ownerName.equals(soulOwnerName(stack))) {
				return true;
			}
		}
		return false;
	}

	private static void putBack(ChestBlockEntity chest, String ownerName) {
		for (int i = 0; i < chest.getContainerSize(); i++) {
			if (chest.getItem(i).isEmpty()) {
				chest.setItem(i, ModItems.createSoul(ownerName));
				return;
			}
		}
	}

	private static void tickChests() {
		ServerLevel level = server().overworld();
		Iterator<RealmState.SoulChest> it = state().data.soulChests.iterator();
		boolean changed = false;
		while (it.hasNext()) {
			RealmState.SoulChest sc = it.next();
			BlockPos pos = new BlockPos(sc.x, sc.y, sc.z);
			if (!level.hasChunkAt(pos)) {
				continue;
			}

			// Kiste abgebaut: Besitzer lebt sofort mit seiner Beute am Kistenort wieder.
			if (!level.getBlockState(pos).is(Blocks.CHEST)) {
				for (ItemEntity item : level.getEntitiesOfClass(ItemEntity.class, new AABB(pos).inflate(4.0))) {
					if (isSoulItem(item.getItem()) && sc.ownerName.equals(soulOwnerName(item.getItem()))) {
						item.discard();
					}
				}
				List<ItemStack> loot = takeCorpseOf(sc.owner);
				reviveAt(sc.owner, OVERWORLD, sc.x + 0.5, sc.y, sc.z + 0.5, loot);
				server().getPlayerList().broadcastSystemMessage(Component.literal(
						"Die Seelen-Kiste von " + sc.ownerName + " wurde zerstört - er lebt wieder!"), false);
				it.remove();
				changed = true;
				continue;
			}

			BlockEntity blockEntity = level.getBlockEntity(pos);
			if (!(blockEntity instanceof ChestBlockEntity chest) || chestHasSoul(chest, sc.ownerName)) {
				continue;
			}

			// Die Seele wurde herausgenommen: wer hat sie?
			ServerPlayer taker = null;
			for (ServerPlayer player : level.players()) {
				if (player.distanceToSqr(sc.x + 0.5, sc.y, sc.z + 0.5) <= 144.0
						&& countSoulItems(player, sc.ownerName) > 0) {
					taker = player;
					break;
				}
			}
			if (taker == null) {
				continue;
			}

			boolean isOwner = id(taker).equals(sc.owner);
			if (!isOwner) {
				int other = 0;
				for (int i = 0; i < taker.getInventory().getContainerSize(); i++) {
					ItemStack stack = taker.getInventory().getItem(i);
					if (!stack.isEmpty() && !(isSoulItem(stack) && sc.ownerName.equals(soulOwnerName(stack)))) {
						other++;
					}
				}
				if (other > 0) {
					removeSoulItems(taker, sc.ownerName);
					putBack(chest, sc.ownerName);
					RealmManager.tell(taker, "Leere zuerst dein ganzes Inventar, bevor du eine fremde Seele nimmst.");
					continue;
				}
			}

			removeSoulItems(taker, sc.ownerName);
			List<ItemStack> loot = takeCorpseOf(sc.owner);
			if (isOwner) {
				reviveAt(sc.owner, OVERWORLD, sc.x + 0.5, sc.y, sc.z + 0.5, loot);
				server().getPlayerList().broadcastSystemMessage(Component.literal(
						sc.ownerName + " hat seine Seele zurückgeholt und lebt wieder!"), false);
			} else {
				// Strafe: der Dieb kommt in die Schattenwelt, der wahre Besitzer erscheint an seinem Todesort.
				double x = taker.getX();
				double y = taker.getY();
				double z = taker.getZ();
				String dim = taker.level().dimension().identifier().toString();

				RealmState.Entry entry = new RealmState.Entry();
				entry.key = id(taker);
				entry.type = "player";
				entry.killer = "";
				entry.orphan = true;
				entry.name = taker.getName().getString();
				entry.dim = dim;
				entry.x = x;
				entry.y = y;
				entry.z = z;
				entry.yRot = taker.getYRot();
				entry.xRot = taker.getXRot();
				entry.gameMode = taker.gameMode.getGameModeForPlayer().getName();
				state().data.entries.put(entry.key, entry);
				RealmManager.sendToRealm(taker);

				reviveAt(sc.owner, dim, x, y, z, loot);
				server().getPlayerList().broadcastSystemMessage(Component.literal(
						taker.getName().getString() + " hat die Seele von " + sc.ownerName
								+ " gestohlen und ist im Schatten gelandet. " + sc.ownerName + " ist zurück!"), false);
			}
			it.remove();
			changed = true;
		}
		if (changed) {
			state().save();
		}
	}

	// ================================================================== Elixier-Schwert

	static void handleElixir(ServerPlayer player, String action) {
		if (state() == null || server() == null) {
			return;
		}
		if (!(player.getMainHandItem().getItem() instanceof ElixirSwordItem) || isGhost(player)) {
			return;
		}
		RealmState.SoulInfo info = info(player);
		if (info.stolen < ELIXIR_MIN_SOULS && !info.evilSoul) {
			RealmManager.tell(player, "Dir fehlen Seelen (" + info.stolen + "/" + ELIXIR_MIN_SOULS
					+ ") oder eine ganz bösartige Seele.");
			return;
		}
		long now = server().getTickCount();
		UUID uuid = player.getUUID();

		if (action.equals("elixir_dark")) {
			if (darkReady.getOrDefault(uuid, 0L) > now) {
				RealmManager.tell(player, "Darkness lädt noch (" + (darkReady.get(uuid) - now) / 20 + " s).");
				return;
			}
			darkReady.put(uuid, now + ABILITY_COOLDOWN);
			for (ServerPlayer other : server().getPlayerList().getPlayers()) {
				if (other != player && other.level() == player.level() && other.distanceTo(player) <= 32.0F) {
					other.addEffect(new MobEffectInstance(MobEffects.DARKNESS, (int) DARKNESS_TICKS, 0, false, true));
				}
			}
			RealmManager.tell(player, "Darkness! Ein Dunkel wie vom Warden liegt auf deinen Feinden.");
		} else if (action.equals("elixir_bond")) {
			if (bondReady.getOrDefault(uuid, 0L) > now) {
				RealmManager.tell(player, "Der Bund lädt noch (" + (bondReady.get(uuid) - now) / 20 + " s).");
				return;
			}
			Hit hit = hits.get(uuid);
			ServerPlayer target = hit == null ? null : server().getPlayerList().getPlayer(hit.victim());
			if (hit == null || target == null || now - hit.tick() > HIT_MEMORY) {
				RealmManager.tell(player, "Schlage zuerst deinen Feind mit dem Elixier-Schwert.");
				return;
			}
			bondReady.put(uuid, now + ABILITY_COOLDOWN);
			bonds.put(uuid, new Bond(target.getUUID(), now + BOND_TICKS));
			RealmManager.tell(player, "Bund geschlossen: " + target.getName().getString() + " wird zu dir gezogen.");
			RealmManager.tell(target, "Ein dunkler Bund zieht dich zu " + player.getName().getString() + "!");
		}
	}

	private static void tickBonds(long now) {
		if (bonds.isEmpty()) {
			return;
		}
		Iterator<Map.Entry<UUID, Bond>> it = bonds.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<UUID, Bond> entry = it.next();
			ServerPlayer wielder = server().getPlayerList().getPlayer(entry.getKey());
			ServerPlayer target = server().getPlayerList().getPlayer(entry.getValue().target());
			if (wielder == null || target == null || !wielder.isAlive() || !target.isAlive()
					|| now >= entry.getValue().until() || wielder.level() != target.level()) {
				it.remove();
				continue;
			}
			double dx = wielder.getX() - target.getX();
			double dz = wielder.getZ() - target.getZ();
			double dist = Math.sqrt(dx * dx + dz * dz);
			if (dist > BOND_FREE_DISTANCE) {
				double step = Math.min(1.5, dist - BOND_FREE_DISTANCE + 0.5);
				ServerLevel level = server().getLevel(target.level().dimension());
				if (level != null) {
					target.teleportTo(level, target.getX() + dx / dist * step, target.getY(),
							target.getZ() + dz / dist * step, Set.of(), target.getYRot(), target.getXRot(), true);
				}
			}
		}
	}

	// ================================================================== Anzeige-Sync

	private static void sync(ServerPlayer player, boolean force) {
		RealmState.SoulInfo info = info(player);
		String text = rankIndex(info.karma) + "|" + info.karma + "|" + (info.hasSoul ? 0 : 1)
				+ "|" + info.stolen + "|" + (info.evilSoul ? 1 : 0);
		if (!force && text.equals(lastSent.get(player.getUUID()))) {
			return;
		}
		lastSent.put(player.getUUID(), text);
		ServerPlayNetworking.send(player, new SoulPayload(text));
	}

	// ================================================================== Tick

	static void tick(long tick) {
		if (state() == null || server() == null) {
			return;
		}
		if (tick % 5 == 0) {
			tickRitual();
			tickBonds(tick);
		}
		if (tick % 10 == 0) {
			tickChests();
		}
		if (tick % 20 == 0) {
			for (ServerPlayer player : server().getPlayerList().getPlayers()) {
				if (isGhost(player)) {
					if (!player.hasEffect(MobEffects.INVISIBILITY)
							|| player.gameMode.getGameModeForPlayer() != GameType.ADVENTURE) {
						applyGhost(player);
					} else {
						for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
							ItemStack stack = player.getInventory().getItem(i);
							if (!stack.isEmpty() && !isSoulItem(stack)) {
								player.getInventory().setItem(i, ItemStack.EMPTY);
							}
						}
					}
				}
				sync(player, false);
			}
		}
	}
}
