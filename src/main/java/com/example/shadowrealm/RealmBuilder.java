package com.example.shadowrealm;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Baut die Schattenwelt Schritt für Schritt (ein Bauabschnitt pro Tick) als runde Stadt auf:
 *   - Spawn-Platz bei (0, 0), davon geradeaus (nach Süden, +z) die Prachtstraße zur riesigen Burg
 *   - links der Straße die Kathedrale, rechts die Arena
 *   - hinter dem Spawn der Schattentempel, links davon der Schwarzmarkt, rechts das Gefängnis
 *   - alle übrigen Felder mit ~27 verschiedenen Bauwerken, so verteilt, dass sich Nachbarn nie gleichen
 *   - Ringstraße und eine riesige runde Schattenmauer um die ganze Welt
 * Der Fortschritt wird gespeichert, ein Neustart setzt den Bau fort.
 */
final class RealmBuilder {
	/** Erster freier Block über dem Boden (Schichten: 0 Bedrock, 1-3 Tiefenschiefer, 4-5 Blackstone, 6 poliert). */
	private static final int G = 7;
	private static final int CELL = 40;
	private static final int TOP = 70;
	private static final int VERSION = 4;

	/** Felder, deren Mitte weiter als das vom Spawn entfernt ist, werden nicht bebaut (rund statt eckig). */
	private static final int R_CELLS = 310;
	/** Die Schattenmauer */
	private static final int R_WALL = 345;
	private static final int GRID = 8;

	// Burg: Felder x -2..2, z 4..7 => x -100..99, z 140..299
	private static final int CASTLE_HW = 92;
	private static final int CASTLE_ZA = 144; // Außenmauer vorne (Tor zum Spawn)
	private static final int CASTLE_ZB = 296; // Außenmauer hinten
	// Kathedrale: Felder x -3..-2, z 1..2 => x -140..-61, z 20..99
	private static final int CATHEDRAL_X = -100;
	private static final int CATHEDRAL_Z = 60;
	// Arena: Felder x 2..3, z 1..2 => x 60..139, z 20..99
	private static final int ARENA_X = 100;
	private static final int ARENA_Z = 60;

	private static ServerLevel level;
	private static Random random;

	private RealmBuilder() {
	}

	private static final int PLOT = 0;
	private static final int CASTLE = 1;
	private static final int CATHEDRAL = 2;
	private static final int PLAZA = 3;
	private static final int ROADS_X = 4;
	private static final int ROADS_Z = 5;
	private static final int AVENUE = 6;
	private static final int ARENA = 7;
	private static final int MARKET = 8;
	private static final int PRISON = 9;
	private static final int TEMPLE = 10;
	private static final int WALL = 11;
	private static final int WASTE = 12;

	private static final int CASTLE_PARTS = 22;

	private static boolean isCastleCell(int i, int j) {
		return i >= -2 && i <= 2 && j >= 4 && j <= 7;
	}

	private static boolean isCathedralCell(int i, int j) {
		return (i == -3 || i == -2) && (j == 1 || j == 2);
	}

	private static boolean isArenaCell(int i, int j) {
		return (i == 2 || i == 3) && (j == 1 || j == 2);
	}

	private static boolean isMarketCell(int i, int j) {
		return i >= -4 && i <= -2 && (j == -2 || j == -1);
	}

	private static boolean isPrisonCell(int i, int j) {
		return i >= 2 && i <= 4 && (j == -2 || j == -1);
	}

	private static boolean isTempleCell(int i, int j) {
		return i >= -1 && i <= 1 && j >= -3 && j <= -1;
	}

	private static boolean reserved(int i, int j) {
		return (i == 0 && j >= 0 && j <= 3) || isCastleCell(i, j) || isCathedralCell(i, j) || isArenaCell(i, j)
				|| isMarketCell(i, j) || isPrisonCell(i, j) || isTempleCell(i, j);
	}

	private static boolean inDisc(int i, int j) {
		double x = i * CELL;
		double z = j * CELL;
		return Math.sqrt(x * x + z * z) <= R_CELLS;
	}

	private static final int TYPE_COUNT = 27;
	private static Map<Long, Integer> assignment;

	private static long key(int i, int j) {
		return ((long) (i + 100) << 32) | (long) (j + 100);
	}

	/** Verteilt die Bauwerke gleichmäßig; kein Typ steht neben dem gleichen Typ. */
	private static void buildAssignment() {
		assignment = new HashMap<>();
		Random r = new Random(4242L);
		int[] used = new int[TYPE_COUNT];
		List<int[]> cells = new ArrayList<>();
		for (int j = -GRID; j <= GRID; j++) {
			for (int i = -GRID; i <= GRID; i++) {
				if (inDisc(i, j) && !reserved(i, j)) {
					cells.add(new int[]{i, j});
				}
			}
		}
		cells.sort((a, b) -> Integer.compare(a[0] * a[0] + a[1] * a[1], b[0] * b[0] + b[1] * b[1]));
		for (int[] c : cells) {
			int best = -1;
			int bestScore = Integer.MAX_VALUE;
			for (int t = 0; t < TYPE_COUNT; t++) {
				boolean clash = false;
				for (int dx = -2; dx <= 2 && !clash; dx++) {
					for (int dz = -2; dz <= 2; dz++) {
						Integer other = assignment.get(key(c[0] + dx, c[1] + dz));
						if (other != null && other == t && Math.max(Math.abs(dx), Math.abs(dz)) <= 1) {
							clash = true;
							break;
						}
					}
				}
				if (clash) {
					continue;
				}
				int score = used[t] * 100 + r.nextInt(50);
				if (score < bestScore) {
					bestScore = score;
					best = t;
				}
			}
			if (best < 0) {
				best = r.nextInt(TYPE_COUNT);
			}
			used[best]++;
			assignment.put(key(c[0], c[1]), best);
		}
	}

	private static List<int[]> steps() {
		List<int[]> list = new ArrayList<>();
		list.add(new int[]{PLAZA, 0, 0});
		list.add(new int[]{AVENUE, 0, 0});
		for (int part = 0; part < CASTLE_PARTS; part++) {
			list.add(new int[]{CASTLE, part, 0});
		}
		list.add(new int[]{CATHEDRAL, 0, 0});
		list.add(new int[]{ARENA, 0, 0});
		for (int part = 0; part < 4; part++) {
			list.add(new int[]{MARKET, part, 0});
		}
		for (int part = 0; part < 5; part++) {
			list.add(new int[]{PRISON, part, 0});
		}
		for (int part = 0; part < 4; part++) {
			list.add(new int[]{TEMPLE, part, 0});
		}
		for (int j = -GRID; j <= GRID; j++) {
			for (int i = -GRID; i <= GRID; i++) {
				if (inDisc(i, j) && !reserved(i, j) && !(i == 0 && j == 0)) {
					list.add(new int[]{PLOT, i, j});
				}
			}
		}
		list.add(new int[]{ROADS_X, 0, 0});
		list.add(new int[]{ROADS_Z, 0, 0});
		for (int part = 0; part < 4; part++) {
			list.add(new int[]{WALL, part, 0});
		}
		list.add(new int[]{WALL, 4, 0});
		list.add(new int[]{WASTE, 0, 0});
		return list;
	}

	/** Wird jeden Tick aufgerufen und erledigt höchstens einen Bauabschnitt. */
	static void tick(MinecraftServer server, RealmState state) {
		if (state == null) {
			return;
		}
		if (state.data.buildVersion != VERSION) {
			state.data.buildVersion = VERSION;
			state.data.builtSteps = 0;
			state.data.realmBuildDone = false;
			state.save();
		}
		if (state.data.realmBuildDone) {
			return;
		}
		ServerLevel realm = server.getLevel(ShadowRealm.REALM);
		if (realm == null) {
			return;
		}
		if (assignment == null) {
			buildAssignment();
		}
		List<int[]> steps = steps();
		int index = state.data.builtSteps;
		if (index >= steps.size()) {
			state.data.realmBuildDone = true;
			state.save();
			return;
		}
		level = realm;
		try {
			random = new Random(777L + index * 31L);
			int[] step = steps.get(index);
			switch (step[0]) {
				case PLAZA -> plaza();
				case AVENUE -> avenue();
				case CASTLE -> castlePart(step[1]);
				case CATHEDRAL -> cathedral();
				case ARENA -> arena();
				case MARKET -> marketPart(step[1]);
				case PRISON -> prisonPart(step[1]);
				case TEMPLE -> templePart(step[1]);
				case PLOT -> plot(step[1], step[2]);
				case ROADS_X -> roads(true);
				case ROADS_Z -> roads(false);
				case WALL -> wallPart(step[1]);
				case WASTE -> waste();
				default -> {
				}
			}
		} catch (RuntimeException e) {
			System.err.println("[shadowrealm] Bauschritt " + index + " fehlgeschlagen: " + e);
		} finally {
			level = null;
		}
		state.data.builtSteps = index + 1;
		if (state.data.builtSteps % 10 == 0 || state.data.builtSteps >= steps.size()) {
			state.save();
		}
	}

	private static int rnd(int lo, int hi) {
		return lo + random.nextInt(hi - lo + 1);
	}

	// ================================================================== Felder

	private static void plot(int i, int j) {
		int cx = i * CELL;
		int cz = j * CELL;
		clearArea(cx - 20, cz - 20, cx + 19, cz + 19);
		scatter(cx - 18, cz - 18, cx + 17, cz + 17, 55);
		Integer type = assignment.get(key(i, j));
		switch (type == null ? 0 : type) {
			case 0 -> bigTower(cx, cz);
			case 1 -> hall(cx, cz);
			case 2 -> outpost(cx, cz);
			case 3 -> graveyard(cx, cz);
			case 4 -> pillarCourt(cx, cz);
			case 5 -> pyramid(cx, cz);
			case 6 -> spire(cx, cz);
			case 7 -> market(cx, cz);
			case 8 -> coliseum(cx, cz);
			case 9 -> bridgeTowers(cx, cz);
			case 10 -> library(cx, cz);
			case 11 -> clockTower(cx, cz);
			case 12 -> cloister(cx, cz);
			case 13 -> gallows(cx, cz);
			case 14 -> ruinedChapel(cx, cz);
			case 15 -> obeliskField(cx, cz);
			case 16 -> tripleArch(cx, cz);
			case 17 -> brazierPlaza(cx, cz);
			case 18 -> mausoleum(cx, cz);
			case 19 -> smallKeep(cx, cz);
			case 20 -> palisadeFort(cx, cz);
			case 21 -> ruinedVillage(cx, cz);
			case 22 -> fountainPlaza(cx, cz);
			case 23 -> stoneCircle(cx, cz);
			case 24 -> forgeComplex(cx, cz);
			case 25 -> maze(cx, cz);
			default -> spiralTower(cx, cz);
		}
	}

	// ================================================================== Hilfsfunktionen

	private static void set(int x, int y, int z, Block block) {
		if (y < 0 || y > 255) {
			return;
		}
		BlockPos pos = new BlockPos(x, y, z);
		BlockState wanted = block.defaultBlockState();
		if (level.getBlockState(pos) != wanted) {
			level.setBlock(pos, wanted, 2);
		}
	}

	private static void fill(int x1, int y1, int z1, int x2, int y2, int z2, Block block) {
		for (int x = Math.min(x1, x2); x <= Math.max(x1, x2); x++) {
			for (int y = Math.min(y1, y2); y <= Math.max(y1, y2); y++) {
				for (int z = Math.min(z1, z2); z <= Math.max(z1, z2); z++) {
					set(x, y, z, block);
				}
			}
		}
	}

	private static Block wall() {
		int r = random.nextInt(10);
		if (r < 5) {
			return Blocks.POLISHED_BLACKSTONE_BRICKS;
		}
		if (r < 8) {
			return Blocks.CRACKED_POLISHED_BLACKSTONE_BRICKS;
		}
		return Blocks.BLACKSTONE;
	}

	/** Hohler Quader aus Mauersteinen. */
	private static void box(int x1, int y1, int z1, int x2, int y2, int z2) {
		for (int x = x1; x <= x2; x++) {
			for (int y = y1; y <= y2; y++) {
				for (int z = z1; z <= z2; z++) {
					boolean edge = x == x1 || x == x2 || z == z1 || z == z2 || y == y1 || y == y2;
					set(x, y, z, edge ? wall() : Blocks.AIR);
				}
			}
		}
	}

	/** Hohler Quader ohne Dach (oben offen). */
	private static void shell(int x1, int y1, int z1, int x2, int y2, int z2) {
		for (int x = x1; x <= x2; x++) {
			for (int y = y1; y <= y2; y++) {
				for (int z = z1; z <= z2; z++) {
					boolean edge = x == x1 || x == x2 || z == z1 || z == z2;
					set(x, y, z, edge ? wall() : Blocks.AIR);
				}
			}
		}
	}

	private static void battlements(int x1, int z1, int x2, int z2, int y) {
		for (int x = x1; x <= x2; x++) {
			for (int z = z1; z <= z2; z++) {
				boolean edge = x == x1 || x == x2 || z == z1 || z == z2;
				if (edge && (x + z) % 2 == 0) {
					set(x, y, z, Blocks.POLISHED_BLACKSTONE_BRICKS);
				}
			}
		}
	}

	/** Stufen-Pyramide als Dach. half = halbe Kantenlänge der untersten Stufe. */
	private static void roof(int cx, int y, int cz, int half) {
		for (int k = 0; half - k >= 0; k++) {
			fill(cx - (half - k), y + k, cz - (half - k), cx + (half - k), y + k, cz + (half - k),
					k % 2 == 0 ? Blocks.BLACKSTONE : Blocks.POLISHED_BLACKSTONE_BRICKS);
		}
		set(cx, y + half + 1, cz, Blocks.CRYING_OBSIDIAN);
	}

	private static void lantern(int x, int y, int z) {
		set(x, y, z, Blocks.SOUL_LANTERN);
	}

	private static void post(int x, int y, int z, int h) {
		fill(x, y, z, x, y + h - 1, z, Blocks.POLISHED_BLACKSTONE_BRICKS);
		lantern(x, y + h, z);
	}

	/** Mehrstöckiger Turm: Mauern, Böden alle 6 Blöcke mit Loch, Fenster, Dach. */
	private static void tower(int cx, int cz, int half, int y, int height, boolean roofOn) {
		box(cx - half, y, cz - half, cx + half, y + height, cz + half);
		if (!roofOn) {
			fill(cx - half + 1, y + height, cz - half + 1, cx + half - 1, y + height, cz + half - 1, Blocks.AIR);
			battlements(cx - half, cz - half, cx + half, cz + half, y + height);
		}
		for (int h = 6; h < height; h += 6) {
			fill(cx - half + 1, y + h, cz - half + 1, cx + half - 1, y + h, cz + half - 1, Blocks.POLISHED_BLACKSTONE_BRICKS);
			fill(cx - half + 1, y + h, cz - half + 1, cx - half + 1, y + h, cz - half + 1, Blocks.AIR);
			set(cx - half + 1, y + h - 1, cz + half - 1, Blocks.SOUL_LANTERN);
		}
		for (int h = 3; h < height; h += 6) {
			set(cx, y + h, cz - half, Blocks.IRON_BARS);
			set(cx, y + h, cz + half, Blocks.IRON_BARS);
			set(cx - half, y + h, cz, Blocks.IRON_BARS);
			set(cx + half, y + h, cz, Blocks.IRON_BARS);
		}
		fill(cx, y + 1, cz + half, cx, y + 3, cz + half, Blocks.AIR); // Tür nach Süden
		if (roofOn) {
			roof(cx, y + height + 1, cz, half);
		}
	}

	private static void ruin(int x1, int z1, int x2, int z2, int y, int fromH, int toH) {
		for (int x = x1; x <= x2; x++) {
			for (int z = z1; z <= z2; z++) {
				boolean edge = x == x1 || x == x2 || z == z1 || z == z2;
				if (!edge) {
					continue;
				}
				for (int h = fromH; h <= toH; h++) {
					if (random.nextInt(100) < 5 + (h - fromH) * 3) {
						set(x, y + h, z, Blocks.AIR);
					}
				}
			}
		}
	}

	// ================================================================== Boden und Dekoration

	/** Stellt den Boden (Schichten) wieder her und räumt alles darüber frei. */
	private static void clearArea(int x1, int z1, int x2, int z2) {
		for (int x = x1; x <= x2; x++) {
			for (int z = z1; z <= z2; z++) {
				set(x, 0, z, Blocks.BEDROCK);
				set(x, 1, z, Blocks.DEEPSLATE);
				set(x, 2, z, Blocks.DEEPSLATE);
				set(x, 3, z, Blocks.DEEPSLATE);
				set(x, 4, z, Blocks.BLACKSTONE);
				set(x, 5, z, Blocks.BLACKSTONE);
				set(x, 6, z, Blocks.POLISHED_BLACKSTONE);
				for (int y = G; y <= TOP; y++) {
					set(x, y, z, Blocks.AIR);
				}
			}
		}
	}

	/** Streut Dekoration (Spitzen, Laternenpfähle, Geröll, tote Bäume, Seelenfeuer) auf eine Fläche. */
	private static void scatter(int x1, int z1, int x2, int z2, int count) {
		for (int n = 0; n < count; n++) {
			int x = x1 + random.nextInt(x2 - x1 + 1);
			int z = z1 + random.nextInt(z2 - z1 + 1);
			switch (random.nextInt(6)) {
				case 0 -> {
					int h = 2 + random.nextInt(5);
					fill(x, G, z, x, G + h - 1, z, Blocks.BLACKSTONE);
					set(x, G + h, z, random.nextInt(3) == 0 ? Blocks.CRYING_OBSIDIAN : Blocks.BLACKSTONE);
				}
				case 1 -> post(x, G, z, 3);
				case 2 -> {
					set(x, G, z, Blocks.COBBLED_DEEPSLATE);
					set(x + 1, G, z, Blocks.BLACKSTONE);
					set(x, G, z + 1, Blocks.CRACKED_DEEPSLATE_BRICKS);
					set(x, G + 1, z, Blocks.BLACKSTONE_SLAB);
				}
				case 3 -> {
					int h = 4 + random.nextInt(3);
					fill(x, G, z, x, G + h, z, Blocks.DEEPSLATE);
					set(x + 1, G + h - 1, z, Blocks.BLACKSTONE_WALL);
					set(x - 1, G + h - 2, z, Blocks.BLACKSTONE_WALL);
					set(x, G + h - 2, z + 1, Blocks.BLACKSTONE_WALL);
				}
				case 4 -> {
					set(x, G, z, Blocks.SOUL_CAMPFIRE);
					set(x + 1, G, z, Blocks.BLACKSTONE);
					set(x - 1, G, z, Blocks.BLACKSTONE);
				}
				default -> {
					set(x, G, z, Blocks.BLACKSTONE_WALL);
					set(x, G + 1, z, Blocks.BLACKSTONE_WALL);
					set(x, G, z + 1, Blocks.COBBLED_DEEPSLATE);
				}
			}
		}
	}

	private static void bigTower(int cx, int cz) {
		int h = 18 + random.nextInt(14);
		tower(cx, cz, 5, G, h, random.nextBoolean());
		ruin(cx - 5, cz - 5, cx + 5, cz + 5, G, 8, h);
		tower(cx - 12, cz + 8, 2, G, 9, true);
		tower(cx + 12, cz - 8, 2, G, 11, true);
		fill(cx - 10, G, cz + 1, cx - 6, G, cz + 1, Blocks.POLISHED_BLACKSTONE_BRICKS);
	}

	private static void hall(int cx, int cz) {
		int x1 = cx - 11;
		int x2 = cx + 11;
		int z1 = cz - 6;
		int z2 = cz + 6;
		box(x1, G, z1, x2, G + 9, z2);
		for (int x = x1 + 4; x < x2; x += 4) {
			fill(x, G + 1, z1 + 2, x, G + 8, z1 + 2, Blocks.POLISHED_BLACKSTONE_BRICKS);
			fill(x, G + 1, z2 - 2, x, G + 8, z2 - 2, Blocks.POLISHED_BLACKSTONE_BRICKS);
			lantern(x, G + 7, cz);
		}
		for (int x = x1 + 2; x < x2; x += 4) {
			set(x, G + 3, z1, Blocks.IRON_BARS);
			set(x, G + 3, z2, Blocks.IRON_BARS);
		}
		fill(cx - 1, G, z2, cx + 1, G + 3, z2, Blocks.AIR);
		fill(x1, G, cz - 1, x1, G + 3, cz + 1, Blocks.AIR);
		fill(x2, G, cz - 1, x2, G + 3, cz + 1, Blocks.AIR);
		set(cx, G, cz - 4, Blocks.CRYING_OBSIDIAN);
		fill(cx - 1, G, cz - 5, cx + 1, G, cz - 5, Blocks.POLISHED_BLACKSTONE_BRICKS);
		for (int k = 0; k < 9; k++) {
			set(x1 + random.nextInt(23), G + 9, z1 + random.nextInt(13), Blocks.AIR);
		}
		tower(x1 - 1, z1 - 1, 2, G, 13, true);
		tower(x2 + 1, z2 + 1, 2, G, 13, true);
	}

	private static void outpost(int cx, int cz) {
		int r = 13;
		shell(cx - r, G, cz - r, cx + r, G + 7, cz + r);
		battlements(cx - r, cz - r, cx + r, cz + r, G + 8);
		fill(cx - 2, G, cz + r, cx + 2, G + 5, cz + r, Blocks.AIR);
		fill(cx - 2, G + 6, cz + r, cx + 2, G + 6, cz + r, Blocks.CHISELED_POLISHED_BLACKSTONE);
		for (int sx : new int[]{-r, r}) {
			for (int sz : new int[]{-r, r}) {
				tower(cx + sx, cz + sz, 3, G, 14, true);
			}
		}
		// Brunnen
		shell(cx - 2, G, cz - 2, cx + 2, G + 1, cz + 2);
		fill(cx - 1, G, cz - 1, cx + 1, G, cz + 1, Blocks.SOUL_SAND);
		set(cx, G + 1, cz, Blocks.SOUL_CAMPFIRE);
		// Wohnhaus im Hof
		box(cx - 9, G, cz - 9, cx - 4, G + 5, cz - 5);
		fill(cx - 7, G, cz - 5, cx - 7, G + 2, cz - 5, Blocks.AIR);
		roof(cx - 7, G + 6, cz - 7, 3);
		box(cx + 4, G, cz - 9, cx + 9, G + 5, cz - 5);
		fill(cx + 6, G, cz - 5, cx + 6, G + 2, cz - 5, Blocks.AIR);
		roof(cx + 6, G + 6, cz - 7, 3);
	}

	private static void graveyard(int cx, int cz) {
		for (int gx = -7; gx <= 7; gx += 3) {
			for (int gz = 2; gz <= 14; gz += 3) {
				set(cx + gx, G, cz + gz, Blocks.BLACKSTONE_WALL);
				set(cx + gx, G + 1, cz + gz, Blocks.BLACKSTONE_WALL);
				set(cx + gx, G, cz + gz + 1, Blocks.COBBLED_DEEPSLATE);
			}
		}
		// Gruft
		box(cx - 6, G, cz - 14, cx + 6, G + 8, cz - 4);
		fill(cx - 1, G, cz - 4, cx + 1, G + 3, cz - 4, Blocks.AIR);
		for (int x = cx - 4; x <= cx + 4; x += 4) {
			fill(x, G, cz - 11, x, G, cz - 8, Blocks.DEEPSLATE_BRICKS);
			set(x, G + 1, cz - 10, Blocks.SOUL_CAMPFIRE);
		}
		roof(cx, G + 9, cz - 9, 6);
		for (int sx : new int[]{-7, 7}) {
			post(cx + sx, G, cz - 3, 4);
		}
	}

	private static void pillarCourt(int cx, int cz) {
		int r = 11;
		for (int n = 0; n < 16; n++) {
			double a = n * Math.PI * 2.0 / 16.0;
			int x = cx + (int) Math.round(Math.cos(a) * r);
			int z = cz + (int) Math.round(Math.sin(a) * r);
			int h = 7 + (n % 3) * 2;
			fill(x, G, z, x, G + h, z, Blocks.POLISHED_BLACKSTONE_BRICKS);
			set(x, G, z, Blocks.CHISELED_POLISHED_BLACKSTONE);
			set(x, G + h + 1, z, Blocks.CRYING_OBSIDIAN);
		}
		for (int k = 0; k < 5; k++) {
			int s = 4 - k;
			fill(cx - s, G + k, cz - s, cx + s, G + k, cz + s, k % 2 == 0 ? Blocks.BLACKSTONE : Blocks.POLISHED_BLACKSTONE_BRICKS);
		}
		fill(cx, G + 5, cz, cx, G + 20, cz, Blocks.POLISHED_BLACKSTONE_BRICKS);
		set(cx, G + 21, cz, Blocks.CRYING_OBSIDIAN);
		lantern(cx + 1, G + 5, cz);
		lantern(cx - 1, G + 5, cz);
	}

	private static void pyramid(int cx, int cz) {
		int half = 14;
		for (int k = 0; k <= half; k++) {
			int s = half - k;
			if (s > 1) {
				shell(cx - s, G + k, cz - s, cx + s, G + k, cz + s);
				fill(cx - s + 1, G + k, cz - s + 1, cx + s - 1, G + k, cz + s - 1, k % 3 == 0 ? Blocks.DEEPSLATE_BRICKS : Blocks.AIR);
			} else {
				fill(cx - s, G + k, cz - s, cx + s, G + k, cz + s, Blocks.POLISHED_BLACKSTONE_BRICKS);
			}
		}
		fill(cx - 1, G, cz + half - 0, cx + 1, G + 3, cz + half, Blocks.AIR);
		for (int k = 0; k < 6; k++) {
			fill(cx - 1, G + k, cz + half + 1 + (5 - k), cx + 1, G + k, cz + half + 1 + (5 - k), Blocks.POLISHED_BLACKSTONE_BRICKS);
		}
		set(cx, G + half + 1, cz, Blocks.CRYING_OBSIDIAN);
		set(cx, G + half + 2, cz, Blocks.SOUL_LANTERN);
		for (int sx : new int[]{-16, 16}) {
			for (int sz : new int[]{-16, 16}) {
				post(cx + sx, G, cz + sz, 5);
			}
		}
	}

	private static void spire(int cx, int cz) {
		box(cx - 4, G, cz - 4, cx + 4, G + 10, cz + 4);
		fill(cx, G, cz + 4, cx, G + 3, cz + 4, Blocks.AIR);
		box(cx - 3, G + 10, cz - 3, cx + 3, G + 24, cz + 3);
		box(cx - 2, G + 24, cz - 2, cx + 2, G + 38, cz + 2);
		for (int h = 14; h <= 36; h += 11) {
			fill(cx - 5, G + h, cz - 5, cx + 5, G + h, cz + 5, Blocks.POLISHED_BLACKSTONE_BRICKS);
			for (int sx : new int[]{-5, 5}) {
				for (int sz : new int[]{-5, 5}) {
					lantern(cx + sx, G + h + 1, cz + sz);
				}
			}
		}
		for (int h = 3; h < 38; h += 5) {
			set(cx, G + h, cz - 4 + (h > 24 ? 2 : (h > 10 ? 1 : 0)), Blocks.IRON_BARS);
		}
		roof(cx, G + 39, cz, 2);
		for (int sx : new int[]{-10, 10}) {
			tower(cx + sx, cz + 4, 2, G, 8, true);
		}
	}

	private static void market(int cx, int cz) {
		for (int row = -1; row <= 1; row++) {
			for (int col = -2; col <= 1; col++) {
				int x = cx + col * 8 + 3;
				int z = cz + row * 10;
				for (int dx : new int[]{0, 4}) {
					for (int dz : new int[]{0, 4}) {
						fill(x + dx, G, z + dz, x + dx, G + 3, z + dz, Blocks.POLISHED_BLACKSTONE_BRICKS);
					}
				}
				fill(x, G + 4, z, x + 4, G + 4, z + 4, Blocks.BLACKSTONE);
				fill(x + 1, G, z + 1, x + 3, G, z + 1, Blocks.DEEPSLATE_BRICKS);
				lantern(x + 2, G + 3, z + 2);
				set(x + 2, G + 4, z + 2, Blocks.AIR);
			}
		}
		tower(cx - 16, cz - 14, 2, G, 12, true);
		tower(cx + 16, cz + 14, 2, G, 12, true);
	}

	private static void coliseum(int cx, int cz) {
		for (int x = -15; x <= 15; x++) {
			for (int z = -15; z <= 15; z++) {
				double d = Math.sqrt(x * x + z * z);
				if (d >= 13.0 && d <= 14.6) {
					boolean gate = Math.abs(x) <= 2 || Math.abs(z) <= 2;
					int h = gate ? 0 : 8;
					for (int y = 0; y < h; y++) {
						set(cx + x, G + y, cz + z, wall());
					}
					if (gate) {
						set(cx + x, G + 6, cz + z, Blocks.CHISELED_POLISHED_BLACKSTONE);
					} else if ((x + z) % 2 == 0) {
						set(cx + x, G + 8, cz + z, Blocks.POLISHED_BLACKSTONE_BRICKS);
					}
				}
				if (d >= 9.0 && d < 13.0 && ((int) d) % 2 == 1) {
					int step = 13 - (int) d;
					fill(cx + x, G, cz + z, cx + x, G + Math.max(0, 3 - step / 2), cz + z, Blocks.DEEPSLATE_TILES);
				}
				if (d < 5.0) {
					set(cx + x, G - 1, cz + z, Blocks.CHISELED_POLISHED_BLACKSTONE);
				}
			}
		}
		set(cx, G, cz, Blocks.CRYING_OBSIDIAN);
		set(cx, G + 1, cz, Blocks.SOUL_CAMPFIRE);
		for (int n = 0; n < 8; n++) {
			double a = n * Math.PI / 4.0 + Math.PI / 8.0;
			post(cx + (int) Math.round(Math.cos(a) * 16), G, cz + (int) Math.round(Math.sin(a) * 16), 6);
		}
	}

	private static void bridgeTowers(int cx, int cz) {
		tower(cx - 10, cz, 3, G, 16, true);
		tower(cx + 10, cz, 3, G, 16, true);
		fill(cx - 7, G + 12, cz - 1, cx + 7, G + 12, cz + 1, Blocks.POLISHED_BLACKSTONE_BRICKS);
		fill(cx - 7, G + 13, cz - 1, cx + 7, G + 13, cz - 1, Blocks.BLACKSTONE_WALL);
		fill(cx - 7, G + 13, cz + 1, cx + 7, G + 13, cz + 1, Blocks.BLACKSTONE_WALL);
		fill(cx - 6, G, cz, cx - 6, G + 11, cz, Blocks.AIR);
		for (int x = cx - 6; x <= cx + 6; x += 4) {
			fill(x, G, cz, x, G + 11, cz, Blocks.POLISHED_BLACKSTONE_BRICKS);
			lantern(x, G + 11, cz + 1);
		}
		fill(cx - 5, G, cz - 1, cx - 5, G + 3, cz + 1, Blocks.AIR);
		tower(cx, cz + 12, 2, G, 8, true);
		tower(cx, cz - 12, 2, G, 10, true);
	}

	private static void plaza() {
		clearArea(-20, -20, 19, 19);
		// Obelisken und Laternen um den Spawn, die Mitte bleibt frei
		for (int n = 0; n < 12; n++) {
			double a = n * Math.PI * 2.0 / 12.0;
			int x = (int) Math.round(Math.cos(a) * 16);
			int z = (int) Math.round(Math.sin(a) * 16);
			int h = 5 + (n % 3) * 2;
			fill(x, G, z, x, G + h, z, Blocks.POLISHED_BLACKSTONE_BRICKS);
			set(x, G, z, Blocks.CHISELED_POLISHED_BLACKSTONE);
			set(x, G + h + 1, z, Blocks.CRYING_OBSIDIAN);
		}
		for (int x = -12; x <= 12; x++) {
			for (int z = -12; z <= 12; z++) {
				double d = Math.sqrt(x * x + z * z);
				if (d > 9.0 && d < 12.0 && ((x + z) & 1) == 0) {
					set(x, G - 1, z, Blocks.CHISELED_POLISHED_BLACKSTONE);
				}
				if (d >= 12.0 && d < 13.0) {
					set(x, G - 1, z, Blocks.CRYING_OBSIDIAN);
				}
			}
		}
		for (int sx : new int[]{-9, 9}) {
			for (int sz : new int[]{-9, 9}) {
				post(sx, G, sz, 3);
			}
		}
		scatter(-19, -19, 19, 19, 25);
	}
	// ================================================================== Weitere Bauwerke (Felder)

	private static void library(int cx, int cz) {
		int w = rnd(10, 13);
		int d = rnd(7, 8);
		box(cx - w, G, cz - d, cx + w, G + 14, cz + d);
		for (int x = cx - w + 2; x <= cx + w - 2; x += 3) {
			fill(x, G, cz - d + 1, x, G + 5, cz - 2, Blocks.BOOKSHELF);
			fill(x, G, cz + 2, x, G + 5, cz + d - 1, Blocks.BOOKSHELF);
			fill(x, G + 8, cz - d + 1, x, G + 12, cz - 2, Blocks.BOOKSHELF);
			fill(x, G + 8, cz + 2, x, G + 12, cz + d - 1, Blocks.BOOKSHELF);
			lantern(x, G + 6, cz);
		}
		fill(cx - w + 1, G + 7, cz - d + 1, cx + w - 1, G + 7, cz - 2, Blocks.POLISHED_BLACKSTONE_BRICKS);
		fill(cx - w + 1, G + 7, cz + 2, cx + w - 1, G + 7, cz + d - 1, Blocks.POLISHED_BLACKSTONE_BRICKS);
		fill(cx - 1, G, cz + d, cx + 1, G + 4, cz + d, Blocks.AIR);
		for (int x = cx - w + 3; x < cx + w; x += 5) {
			for (int h = 4; h <= 11; h += 7) {
				set(x, G + h, cz - d, Blocks.IRON_BARS);
				set(x, G + h, cz + d, Blocks.IRON_BARS);
			}
		}
		tower(cx - w, cz - d, 2, G, 20, true);
		tower(cx + w, cz + d, 2, G, 20, true);
		for (int k = 0; k < 6; k++) {
			set(cx - w + 1, G + k, cz - d + 1 + (k % 3), Blocks.POLISHED_BLACKSTONE_BRICKS);
		}
	}

	private static void clockTower(int cx, int cz) {
		box(cx - 7, G, cz - 7, cx + 7, G + 8, cz + 7);
		fill(cx, G, cz + 7, cx, G + 3, cz + 7, Blocks.AIR);
		tower(cx, cz, 4, G, 38, true);
		for (int dx = -3; dx <= 3; dx++) {
			for (int dy = -3; dy <= 3; dy++) {
				int dd = dx * dx + dy * dy;
				if (dd <= 9) {
					set(cx + dx, G + 28 + dy, cz + 5, dd >= 7 ? Blocks.CHISELED_POLISHED_BLACKSTONE : Blocks.POLISHED_BLACKSTONE);
				}
			}
		}
		set(cx, G + 28, cz + 5, Blocks.CRYING_OBSIDIAN);
		fill(cx, G + 28, cz + 5, cx, G + 31, cz + 5, Blocks.CRYING_OBSIDIAN);
		fill(cx, G + 28, cz + 5, cx + 2, G + 28, cz + 5, Blocks.CRYING_OBSIDIAN);
		for (int s : new int[]{-1, 1}) {
			post(cx + s * 9, G, cz + 4, 4);
		}
	}

	private static void cloister(int cx, int cz) {
		int r = rnd(11, 13);
		for (int x = cx - r; x <= cx + r; x += 3) {
			for (int z : new int[]{cz - r, cz + r}) {
				fill(x, G, z, x, G + 6, z, Blocks.POLISHED_BLACKSTONE_BRICKS);
			}
		}
		for (int z = cz - r; z <= cz + r; z += 3) {
			for (int x : new int[]{cx - r, cx + r}) {
				fill(x, G, z, x, G + 6, z, Blocks.POLISHED_BLACKSTONE_BRICKS);
			}
		}
		for (int x = cx - r; x <= cx + r; x++) {
			for (int z = cz - r; z <= cz + r; z++) {
				boolean ring = Math.max(Math.abs(x - cx), Math.abs(z - cz)) >= r - 1;
				if (ring) {
					set(x, G + 7, z, wall());
				}
			}
		}
		fill(cx - r + 2, G - 1, cz - r + 2, cx + r - 2, G - 1, cz + r - 2, Blocks.SOUL_SOIL);
		shell(cx - 2, G, cz - 2, cx + 2, G + 1, cz + 2);
		fill(cx - 1, G, cz - 1, cx + 1, G, cz + 1, Blocks.WATER);
		fill(cx, G + 1, cz, cx, G + 5, cz, Blocks.POLISHED_BLACKSTONE_BRICKS);
		lantern(cx, G + 6, cz);
		for (int n = 0; n < 6; n++) {
			int tx = cx + rnd(-r + 3, r - 3);
			int tz = cz + rnd(-r + 3, r - 3);
			if (Math.abs(tx - cx) > 3 || Math.abs(tz - cz) > 3) {
				fill(tx, G, tz, tx, G + 4, tz, Blocks.DEEPSLATE);
				set(tx + 1, G + 3, tz, Blocks.BLACKSTONE_WALL);
				set(tx, G + 3, tz - 1, Blocks.BLACKSTONE_WALL);
			}
		}
	}

	private static void gallows(int cx, int cz) {
		for (int k = 0; k < 3; k++) {
			fill(cx - 8 + k, G + k, cz - 8 + k, cx + 8 - k, G + k, cz + 8 - k, Blocks.DEEPSLATE_BRICKS);
		}
		for (int gx = -5; gx <= 5; gx += 5) {
			fill(cx + gx, G + 3, cz, cx + gx, G + 11, cz, Blocks.POLISHED_BLACKSTONE_BRICKS);
			fill(cx + gx, G + 11, cz, cx + gx + 3, G + 11, cz, Blocks.POLISHED_BLACKSTONE_BRICKS);
			fill(cx + gx + 3, G + 7, cz, cx + gx + 3, G + 10, cz, Blocks.IRON_BARS);
			fill(cx + gx + 1, G + 3, cz, cx + gx + 2, G + 3, cz, Blocks.BLACKSTONE_SLAB);
			lantern(cx + gx, G + 12, cz);
		}
		for (int n = 0; n < 14; n++) {
			int x = cx + rnd(-16, 16);
			int z = cz + rnd(-16, 16);
			if (Math.abs(x - cx) > 9 || Math.abs(z - cz) > 9) {
				fill(x, G, z, x, G + 2, z, Blocks.BLACKSTONE_WALL);
			}
		}
		tower(cx + 14, cz - 14, 2, G, 12, true);
		tower(cx - 14, cz + 14, 2, G, 12, true);
	}

	private static void ruinedChapel(int cx, int cz) {
		shell(cx - 6, G, cz - 14, cx + 6, G + 14, cz + 14);
		ruin(cx - 6, cz - 14, cx + 6, cz + 14, G, 3, 14);
		for (int z = cz - 10; z <= cz + 10; z += 5) {
			for (int s : new int[]{-5, 5}) {
				fill(cx + s, G, z, cx + s, G + 11, z, Blocks.POLISHED_BLACKSTONE_BRICKS);
			}
			for (int k = 0; k < 6; k++) {
				if (random.nextInt(3) > 0) {
					set(cx - 5 + k * 2, G + 12 - Math.abs(k - 3), z, Blocks.POLISHED_BLACKSTONE_BRICKS);
				}
			}
		}
		fill(cx - 1, G, cz + 14, cx + 1, G + 4, cz + 14, Blocks.AIR);
		fill(cx - 2, G, cz - 12, cx + 2, G + 1, cz - 11, Blocks.DEEPSLATE_TILES);
		set(cx, G + 2, cz - 12, Blocks.CRYING_OBSIDIAN);
		set(cx, G + 3, cz - 12, Blocks.SOUL_CAMPFIRE);
		tower(cx - 9, cz + 12, 2, G, 16, false);
		ruin(cx - 11, cz + 10, cx - 7, cz + 14, G, 5, 16);
	}

	private static void obeliskField(int cx, int cz) {
		for (int gx = -12; gx <= 12; gx += 6) {
			for (int gz = -12; gz <= 12; gz += 6) {
				int h = rnd(4, 16);
				fill(cx + gx, G, cz + gz, cx + gx, G + h, cz + gz, gx == 0 && gz == 0 ? Blocks.CHISELED_POLISHED_BLACKSTONE : Blocks.POLISHED_BLACKSTONE_BRICKS);
				set(cx + gx, G + h + 1, cz + gz, Blocks.CRYING_OBSIDIAN);
				fill(cx + gx - 1, G, cz + gz - 1, cx + gx + 1, G, cz + gz + 1, Blocks.DEEPSLATE_TILES);
				fill(cx + gx, G, cz + gz, cx + gx, G + h, cz + gz, Blocks.POLISHED_BLACKSTONE_BRICKS);
			}
		}
		fill(cx, G, cz, cx, G + 26, cz, Blocks.POLISHED_BLACKSTONE_BRICKS);
		set(cx, G + 27, cz, Blocks.CRYING_OBSIDIAN);
		for (int k = 0; k < 4; k++) {
			fill(cx - 2 + k / 2, G + k, cz - 2 + k / 2, cx + 2 - k / 2, G + k, cz + 2 - k / 2, Blocks.BLACKSTONE);
		}
	}

	private static void tripleArch(int cx, int cz) {
		for (int a = 0; a < 3; a++) {
			int ax = cx - 12 + a * 12;
			int hgt = 9 + (a == 1 ? 4 : 0);
			for (int s : new int[]{-5, 5}) {
				fill(ax + s - 1, G, cz - 1, ax + s + 1, G + hgt, cz + 1, Blocks.POLISHED_BLACKSTONE_BRICKS);
			}
			for (int dx = -5; dx <= 5; dx++) {
				int arc = (int) Math.round(Math.sqrt(Math.max(0, 25 - dx * dx)));
				fill(ax + dx, G + hgt + arc - 1, cz - 1, ax + dx, G + hgt + 2 + arc, cz + 1, wall());
			}
			lantern(ax, G + hgt, cz);
			set(ax, G + hgt + 8, cz, Blocks.CRYING_OBSIDIAN);
		}
		for (int n = 0; n < 4; n++) {
			post(cx - 18 + n * 12, G, cz + 6, 4);
			post(cx - 18 + n * 12, G, cz - 6, 4);
		}
		fill(cx - 17, G - 1, cz - 2, cx + 17, G - 1, cz + 2, Blocks.CHISELED_POLISHED_BLACKSTONE);
	}

	private static void brazierPlaza(int cx, int cz) {
		for (int n = 0; n < 12; n++) {
			double a = n * Math.PI * 2.0 / 12.0;
			int x = cx + (int) Math.round(Math.cos(a) * 14);
			int z = cz + (int) Math.round(Math.sin(a) * 14);
			fill(x, G, z, x, G + 4, z, Blocks.POLISHED_BLACKSTONE_BRICKS);
			fill(x - 1, G + 5, z - 1, x + 1, G + 5, z + 1, Blocks.DEEPSLATE_TILES);
			set(x, G + 6, z, Blocks.SOUL_CAMPFIRE);
		}
		for (int k = 0; k < 5; k++) {
			int s = 6 - k;
			fill(cx - s, G + k, cz - s, cx + s, G + k, cz + s, k % 2 == 0 ? Blocks.DEEPSLATE_TILES : Blocks.POLISHED_BLACKSTONE_BRICKS);
		}
		fill(cx, G + 5, cz, cx, G + 14, cz, Blocks.POLISHED_BLACKSTONE_BRICKS);
		fill(cx - 2, G + 15, cz - 2, cx + 2, G + 15, cz + 2, Blocks.DEEPSLATE_TILES);
		for (int dx = -2; dx <= 2; dx += 2) {
			for (int dz = -2; dz <= 2; dz += 2) {
				set(cx + dx, G + 16, cz + dz, Blocks.SOUL_CAMPFIRE);
			}
		}
		for (int x = -11; x <= 11; x++) {
			for (int z = -11; z <= 11; z++) {
				double d = Math.sqrt(x * x + z * z);
				if (d > 7.5 && d < 11.0 && ((x + z) & 1) == 0) {
					set(cx + x, G - 1, cz + z, Blocks.CHISELED_POLISHED_BLACKSTONE);
				}
			}
		}
	}

	private static void mausoleum(int cx, int cz) {
		fill(cx - 12, G, cz - 12, cx + 12, G + 2, cz + 12, Blocks.DEEPSLATE_BRICKS);
		box(cx - 9, G + 3, cz - 9, cx + 9, G + 14, cz + 9);
		fill(cx - 8, G + 3, cz - 8, cx + 8, G + 3, cz + 8, Blocks.AIR);
		fill(cx - 8, G + 3, cz - 8, cx + 8, G + 3, cz + 8, Blocks.POLISHED_BLACKSTONE_BRICKS);
		for (int x = cx - 6; x <= cx + 6; x += 4) {
			for (int z = cz - 6; z <= cz + 2; z += 4) {
				fill(x - 1, G + 4, z, x + 1, G + 4, z + 1, Blocks.DEEPSLATE_BRICKS);
				set(x, G + 5, z, Blocks.COBBLED_DEEPSLATE_SLAB);
			}
		}
		fill(cx - 1, G + 3, cz + 9, cx + 1, G + 7, cz + 9, Blocks.AIR);
		for (int x = cx - 8; x <= cx + 8; x += 4) {
			fill(x, G + 3, cz + 11, x, G + 13, cz + 11, Blocks.POLISHED_BLACKSTONE_BRICKS);
		}
		fill(cx - 9, G + 14, cz + 10, cx + 9, G + 15, cz + 12, Blocks.CHISELED_POLISHED_BLACKSTONE);
		roof(cx, G + 15, cz, 8);
		for (int k = 0; k < 3; k++) {
			fill(cx - 3, G + k, cz + 13 + (2 - k), cx + 3, G + k, cz + 13 + (2 - k), Blocks.POLISHED_BLACKSTONE_BRICKS);
		}
		lantern(cx - 10, G + 3, cz + 12);
		lantern(cx + 10, G + 3, cz + 12);
	}

	private static void smallKeep(int cx, int cz) {
		box(cx - 8, G, cz - 8, cx + 8, G + 20, cz + 8);
		battlements(cx - 8, cz - 8, cx + 8, cz + 8, G + 21);
		fill(cx - 1, G, cz + 8, cx + 1, G + 5, cz + 8, Blocks.AIR);
		fill(cx - 7, G + 10, cz - 7, cx + 7, G + 10, cz + 7, Blocks.POLISHED_BLACKSTONE_BRICKS);
		fill(cx - 7, G + 10, cz - 7, cx - 5, G + 10, cz - 5, Blocks.AIR);
		for (int s : new int[]{-1, 1}) {
			for (int t : new int[]{-1, 1}) {
				tower(cx + s * 12, cz + t * 12, 3, G, 24, true);
			}
		}
		shell(cx - 15, G, cz - 15, cx + 15, G + 6, cz + 15);
		battlements(cx - 15, cz - 15, cx + 15, cz + 15, G + 7);
		fill(cx - 2, G, cz + 15, cx + 2, G + 4, cz + 15, Blocks.AIR);
		set(cx, G, cz + 12, Blocks.SOUL_CAMPFIRE);
	}

	private static void palisadeFort(int cx, int cz) {
		int r = 15;
		for (int x = cx - r; x <= cx + r; x++) {
			for (int z = cz - r; z <= cz + r; z++) {
				boolean edge = Math.max(Math.abs(x - cx), Math.abs(z - cz)) == r;
				if (edge && !(Math.abs(x - cx) <= 2 && z > cz)) {
					int h = ((x + z) % 3 == 0) ? 6 : 4;
					fill(x, G, z, x, G + h - 1, z, Blocks.BLACKSTONE_WALL);
					if ((x + z) % 3 == 0) {
						set(x, G + h, z, Blocks.CRYING_OBSIDIAN);
					}
				}
			}
		}
		tower(cx, cz - 4, 3, G, 22, false);
		for (int n = 0; n < 4; n++) {
			int tx = cx + (n % 2 == 0 ? -9 : 9);
			int tz = cz + (n < 2 ? 6 : 10);
			roof(tx, G, tz, 3);
			set(tx, G, tz, Blocks.AIR);
		}
		for (int s : new int[]{-1, 1}) {
			post(cx + s * 3, G, cz + r + 1, 5);
		}
	}

	private static void ruinedVillage(int cx, int cz) {
		int[][] spots = {{-10, -10}, {10, -10}, {-10, 10}, {10, 10}, {0, -12}};
		for (int[] sp : spots) {
			int hw = rnd(3, 4);
			int hd = rnd(3, 5);
			int hh = rnd(4, 6);
			int hx = cx + sp[0];
			int hz = cz + sp[1];
			box(hx - hw, G, hz - hd, hx + hw, G + hh, hz + hd);
			fill(hx, G, hz + hd, hx, G + 2, hz + hd, Blocks.AIR);
			roof(hx, G + hh + 1, hz, Math.max(hw, hd));
			ruin(hx - hw, hz - hd, hx + hw, hz + hd, G, 2, hh);
			if (random.nextBoolean()) {
				set(hx, G, hz, Blocks.SOUL_CAMPFIRE);
			}
		}
		shell(cx - 2, G, cz - 2, cx + 2, G + 1, cz + 2);
		fill(cx - 1, G, cz - 1, cx + 1, G, cz + 1, Blocks.WATER);
		post(cx, G + 1, cz + 4, 4);
	}

	private static void fountainPlaza(int cx, int cz) {
		for (int x = -12; x <= 12; x++) {
			for (int z = -12; z <= 12; z++) {
				double d = Math.sqrt(x * x + z * z);
				if (d <= 8.5 && d > 6.5) {
					fill(cx + x, G, cz + z, cx + x, G + 1, cz + z, Blocks.DEEPSLATE_BRICKS);
				} else if (d <= 6.5) {
					set(cx + x, G, cz + z, Blocks.WATER);
					set(cx + x, G - 1, cz + z, Blocks.DEEPSLATE_TILES);
				} else if (d <= 12.0 && ((x + z) & 1) == 0) {
					set(cx + x, G - 1, cz + z, Blocks.CHISELED_POLISHED_BLACKSTONE);
				}
			}
		}
		fill(cx - 2, G, cz - 2, cx + 2, G + 2, cz + 2, Blocks.DEEPSLATE_BRICKS);
		fill(cx, G + 3, cz, cx, G + 12, cz, Blocks.POLISHED_BLACKSTONE_BRICKS);
		fill(cx - 3, G + 9, cz, cx + 3, G + 9, cz, Blocks.BLACKSTONE_WALL);
		fill(cx, G + 9, cz - 3, cx, G + 9, cz + 3, Blocks.BLACKSTONE_WALL);
		set(cx, G + 13, cz, Blocks.CHISELED_POLISHED_BLACKSTONE);
		set(cx, G + 14, cz, Blocks.CRYING_OBSIDIAN);
		for (int n = 0; n < 8; n++) {
			double a = n * Math.PI / 4.0;
			post(cx + (int) Math.round(Math.cos(a) * 13), G, cz + (int) Math.round(Math.sin(a) * 13), 4);
		}
	}

	private static void stoneCircle(int cx, int cz) {
		for (int n = 0; n < 12; n++) {
			double a = n * Math.PI * 2.0 / 12.0;
			int x = cx + (int) Math.round(Math.cos(a) * 13);
			int z = cz + (int) Math.round(Math.sin(a) * 13);
			int h = rnd(5, 8);
			fill(x, G, z, x, G + h, z, Blocks.DEEPSLATE);
			fill(x + 1, G, z, x + 1, G + h - 2, z, Blocks.DEEPSLATE);
			if (n % 2 == 0) {
				double b = (n + 2) * Math.PI * 2.0 / 12.0;
				int x2 = cx + (int) Math.round(Math.cos(b) * 13);
				int z2 = cz + (int) Math.round(Math.sin(b) * 13);
				fill(Math.min(x, x2), G + h + 1, Math.min(z, z2), Math.max(x, x2), G + h + 1, Math.max(z, z2), Blocks.BLACKSTONE);
			}
		}
		for (int n = 0; n < 6; n++) {
			double a = n * Math.PI / 3.0;
			int x = cx + (int) Math.round(Math.cos(a) * 6);
			int z = cz + (int) Math.round(Math.sin(a) * 6);
			fill(x, G, z, x, G + 2, z, Blocks.BLACKSTONE);
			set(x, G + 3, z, Blocks.SOUL_LANTERN);
		}
		fill(cx - 2, G, cz - 2, cx + 2, G + 1, cz + 2, Blocks.DEEPSLATE_TILES);
		set(cx, G + 2, cz, Blocks.CRYING_OBSIDIAN);
		set(cx, G + 3, cz, Blocks.SOUL_CAMPFIRE);
	}

	private static void forgeComplex(int cx, int cz) {
		box(cx - 9, G, cz - 12, cx + 1, G + 9, cz - 4);
		fill(cx - 4, G, cz - 4, cx - 4, G + 2, cz - 4, Blocks.AIR);
		fill(cx - 8, G + 10, cz - 11, cx - 8, G + 18, cz - 11, Blocks.BLACKSTONE);
		set(cx - 7, G, cz - 10, Blocks.SOUL_CAMPFIRE);
		set(cx - 3, G, cz - 10, Blocks.SOUL_CAMPFIRE);
		fill(cx - 6, G, cz - 6, cx - 4, G, cz - 6, Blocks.DEEPSLATE_BRICKS);
		roof(cx - 4, G + 10, cz - 8, 5);
		box(cx + 4, G, cz - 12, cx + 14, G + 6, cz - 2);
		fill(cx + 4, G, cz - 8, cx + 4, G + 3, cz - 6, Blocks.AIR);
		for (int x = cx - 12; x <= cx + 12; x++) {
			if ((x & 1) == 0) {
				set(x, G, cz + 2, Blocks.BLACKSTONE_WALL);
				set(x, G, cz + 12, Blocks.BLACKSTONE_WALL);
			}
		}
		for (int z = cz + 2; z <= cz + 12; z += 2) {
			set(cx - 12, G, z, Blocks.BLACKSTONE_WALL);
			set(cx + 12, G, z, Blocks.BLACKSTONE_WALL);
			set(cx, G, z, Blocks.BLACKSTONE_WALL);
		}
		box(cx - 14, G, cz + 4, cx - 10, G + 5, cz + 10);
		roof(cx - 12, G + 6, cz + 7, 3);
		post(cx + 8, G, cz + 6, 3);
		post(cx + 8, G, cz + 10, 3);
	}

	private static void maze(int cx, int cz) {
		int n = 8;
		int size = n * 4 + 1;
		int x0 = cx - size / 2;
		int z0 = cz - size / 2;
		for (int x = 0; x < size; x++) {
			for (int z = 0; z < size; z++) {
				for (int y = 0; y < 5; y++) {
					set(x0 + x, G + y, z0 + z, wall());
				}
			}
		}
		boolean[][] visited = new boolean[n][n];
		int[] stack = new int[n * n * 2];
		int sp = 0;
		visited[0][0] = true;
		stack[sp++] = 0;
		stack[sp++] = 0;
		carveCell(x0, z0, 0, 0);
		int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
		while (sp > 0) {
			int ccx = stack[sp - 2];
			int ccz = stack[sp - 1];
			List<int[]> options = new ArrayList<>();
			for (int[] d : dirs) {
				int nx = ccx + d[0];
				int nz = ccz + d[1];
				if (nx >= 0 && nx < n && nz >= 0 && nz < n && !visited[nx][nz]) {
					options.add(d);
				}
			}
			if (options.isEmpty()) {
				sp -= 2;
				continue;
			}
			int[] d = options.get(random.nextInt(options.size()));
			int nx = ccx + d[0];
			int nz = ccz + d[1];
			visited[nx][nz] = true;
			carveCell(x0, z0, nx, nz);
			// Durchgang zwischen den beiden Zellen
			int px = x0 + 1 + ccx * 4 + (d[0] > 0 ? 3 : (d[0] < 0 ? -1 : 0));
			int pz = z0 + 1 + ccz * 4 + (d[1] > 0 ? 3 : (d[1] < 0 ? -1 : 0));
			int wx = d[0] != 0 ? 1 : 3;
			int wz = d[1] != 0 ? 1 : 3;
			fill(px, G, pz, px + wx - 1, G + 3, pz + wz - 1, Blocks.AIR);
			stack[sp++] = nx;
			stack[sp++] = nz;
		}
		// Ein- und Ausgang, Laternen
		fill(x0 + 1, G, z0 + size - 1, x0 + 3, G + 3, z0 + size - 1, Blocks.AIR);
		fill(x0 + size - 4, G, z0, x0 + size - 2, G + 3, z0, Blocks.AIR);
		for (int a = 0; a < n; a += 2) {
			for (int b = 0; b < n; b += 3) {
				set(x0 + 2 + a * 4, G, z0 + 2 + b * 4, Blocks.SOUL_LANTERN);
			}
		}
		fill(x0 + size - 4, G, z0 + 1, x0 + size - 2, G + 1, z0 + 3, Blocks.DEEPSLATE_TILES);
		set(x0 + size - 3, G + 2, z0 + 2, Blocks.CRYING_OBSIDIAN);
	}

	private static void carveCell(int x0, int z0, int ix, int iz) {
		fill(x0 + 1 + ix * 4, G, z0 + 1 + iz * 4, x0 + 3 + ix * 4, G + 3, z0 + 3 + iz * 4, Blocks.AIR);
	}

	private static void spiralTower(int cx, int cz) {
		int h = rnd(26, 32);
		box(cx - 4, G, cz - 4, cx + 4, G + h, cz + 4);
		fill(cx - 3, G + h, cz - 3, cx + 3, G + h, cz + 3, Blocks.AIR);
		battlements(cx - 4, cz - 4, cx + 4, cz + 4, G + h);
		fill(cx, G, cz + 4, cx, G + 3, cz + 4, Blocks.AIR);
		for (int k = 0; k < h * 3; k++) {
			double a = k * 0.3;
			int x = cx + (int) Math.round(Math.cos(a) * 7);
			int z = cz + (int) Math.round(Math.sin(a) * 7);
			int y = G + k / 3;
			set(x, y, z, Blocks.POLISHED_BLACKSTONE_BRICKS);
			set(x + 1, y, z, Blocks.POLISHED_BLACKSTONE_BRICKS);
			set(x, y, z + 1, Blocks.POLISHED_BLACKSTONE_BRICKS);
			if (k % 12 == 0) {
				lantern(x, y + 1, z);
			}
		}
		for (int y = 4; y < h; y += 6) {
			set(cx, G + y, cz - 4, Blocks.IRON_BARS);
			set(cx - 4, G + y, cz, Blocks.IRON_BARS);
		}
		roof(cx, G + h + 1, cz, 4);
	}

	// ================================================================== Straßen

	private static boolean inRect(int x, int z, int x1, int z1, int x2, int z2) {
		return x >= x1 && x <= x2 && z >= z1 && z <= z2;
	}

	private static boolean inMegaStructure(int x, int z) {
		return inRect(x, z, -100, 140, 99, 299)       // Burg
				|| inRect(x, z, -140, 20, -61, 99)    // Kathedrale
				|| inRect(x, z, 60, 20, 139, 99)      // Arena
				|| inRect(x, z, -20, -20, 19, 139)    // Spawn + Prachtstraße
				|| inRect(x, z, -180, -100, -61, -21) // Schwarzmarkt
				|| inRect(x, z, 60, -100, 179, -21)   // Gefängnis
				|| inRect(x, z, -60, -140, 59, -21);  // Tempel
	}

	/** Straßen auf den Linien zwischen den Feldern, nur innerhalb der runden Stadt. */
	private static void roads(boolean alongX) {
		int lim = R_CELLS + 20;
		for (int k = -GRID - 1; k <= GRID; k++) {
			int line = k * CELL + 20;
			for (int t = -lim; t <= lim; t++) {
				int x = alongX ? t : line;
				int z = alongX ? line : t;
				if (Math.sqrt((double) x * x + (double) z * z) > lim || inMegaStructure(x, z)) {
					continue;
				}
				for (int w = -1; w <= 1; w++) {
					int px = alongX ? x : x + w;
					int pz = alongX ? z + w : z;
					set(px, G - 1, pz, (t + w) % 5 == 0 ? Blocks.CHISELED_POLISHED_BLACKSTONE : Blocks.POLISHED_BLACKSTONE_BRICKS);
					for (int y = G; y <= G + 6; y++) {
						set(px, y, pz, Blocks.AIR);
					}
				}
				if (t % 10 == 0) {
					int lx = alongX ? x : x + 2;
					int lz = alongX ? z + 2 : z;
					set(lx, G, lz, Blocks.BLACKSTONE_WALL);
					set(lx, G + 1, lz, Blocks.BLACKSTONE_WALL);
					set(lx, G + 2, lz, Blocks.SOUL_LANTERN);
				}
			}
		}
	}

	// ================================================================== Prachtstraße

	private static void avenue() {
		clearArea(-20, 20, 19, 139);
		for (int z = 20; z <= 139; z++) {
			for (int x = -7; x <= 7; x++) {
				boolean band = z % 8 == 0 || Math.abs(x) == 7;
				boolean mid = Math.abs(x) <= 1;
				set(x, G - 1, z, band ? Blocks.CHISELED_POLISHED_BLACKSTONE : (mid ? Blocks.AMETHYST_BLOCK : Blocks.POLISHED_BLACKSTONE_BRICKS));
			}
			if (z % 4 == 0) {
				set(-8, G - 1, z, Blocks.CRYING_OBSIDIAN);
				set(8, G - 1, z, Blocks.CRYING_OBSIDIAN);
			}
		}
		for (int z = 26; z <= 132; z += 12) {
			int h = 5 + ((z / 12) % 3) * 2;
			for (int s : new int[]{-1, 1}) {
				int x = s * 10;
				fill(x, G, z, x, G + h, z, Blocks.POLISHED_BLACKSTONE_BRICKS);
				set(x, G, z, Blocks.CHISELED_POLISHED_BLACKSTONE);
				set(x, G + h + 1, z, Blocks.CRYING_OBSIDIAN);
				set(s * 8, G, z + 6, Blocks.SOUL_CAMPFIRE);
				post(s * 5, G, z + 3, 3);
			}
		}
		for (int z : new int[]{38, 82, 126}) {
			for (int s : new int[]{-1, 1}) {
				int x = s * 14;
				fill(x - 2, G, z - 2, x + 2, G + 1, z + 2, Blocks.DEEPSLATE_BRICKS);
				fill(x, G + 2, z, x, G + 8, z, Blocks.POLISHED_BLACKSTONE_BRICKS);
				fill(x - 1, G + 7, z, x + 1, G + 7, z, Blocks.BLACKSTONE_WALL);
				set(x, G + 9, z, Blocks.CHISELED_POLISHED_BLACKSTONE);
				set(x, G + 10, z, Blocks.CRYING_OBSIDIAN);
			}
		}
		for (int az : new int[]{58, 108}) {
			for (int s : new int[]{-1, 1}) {
				int x = s * 10;
				fill(x - 1, G, az, x + 1, G + 15, az + 1, Blocks.POLISHED_BLACKSTONE_BRICKS);
				lantern(x, G + 16, az);
			}
			fill(-11, G + 14, az, 11, G + 16, az + 1, Blocks.CHISELED_POLISHED_BLACKSTONE);
			fill(-8, G + 14, az, 8, G + 14, az + 1, Blocks.POLISHED_BLACKSTONE_BRICKS);
			fill(-2, G + 17, az, 2, G + 19, az + 1, Blocks.POLISHED_BLACKSTONE_BRICKS);
			set(0, G + 20, az, Blocks.CRYING_OBSIDIAN);
			for (int x = -6; x <= 6; x += 6) {
				lantern(x, G + 13, az);
			}
		}
		tower(-16, 134, 3, G, 24, true);
		tower(16, 134, 3, G, 24, true);
		scatter(-19, 20, -15, 139, 20);
		scatter(15, 20, 18, 139, 20);
	}

	// ================================================================== Riesenburg (Höhe bis ~190)

	private static void thick(int x1, int z1, int x2, int z2, int h) {
		for (int x = Math.min(x1, x2); x <= Math.max(x1, x2); x++) {
			for (int z = Math.min(z1, z2); z <= Math.max(z1, z2); z++) {
				for (int y = G; y < G + h; y++) {
					set(x, y, z, wall());
				}
			}
		}
	}

	private static void crenel(int x1, int z1, int x2, int z2, int y) {
		for (int x = Math.min(x1, x2); x <= Math.max(x1, x2); x++) {
			for (int z = Math.min(z1, z2); z <= Math.max(z1, z2); z++) {
				if ((x + z) % 2 == 0) {
					set(x, y, z, Blocks.POLISHED_BLACKSTONE_BRICKS);
				}
			}
		}
	}

	/**
	 * Eine Stufe einer Rampe, die pro Block nur einen halben Block steigt (man läuft ohne zu springen hoch).
	 * base = Standhöhe am Anfang, j = Anzahl Halbstufen (ab 1).
	 */
	private static void rampBlock(int x, int z, int base, int j, int supportFrom) {
		int y = (j % 2 == 0) ? base + j / 2 - 1 : base + (j - 1) / 2;
		if (supportFrom >= 0 && supportFrom < y) {
			fill(x, supportFrom, z, x, y - 1, z, Blocks.DEEPSLATE_BRICKS);
		}
		set(x, y, z, (j % 2 == 0) ? Blocks.POLISHED_BLACKSTONE_BRICKS : Blocks.POLISHED_BLACKSTONE_SLAB);
	}

	/**
	 * Turm mit Wendeltreppe an der Innenwand (kein Zwischenboden), Tür nach Süden.
	 * climb = Höhe, die die Treppe überwindet; rot = Startversatz im Ring; mirror = Drehrichtung spiegeln.
	 */
	private static void stairTower(int cx, int cz, int half, int y, int height, int climb, int rot, boolean mirror, boolean withRoof) {
		box(cx - half, y, cz - half, cx + half, y + height, cz + half);
		for (int h = 8; h < height - 4; h += 10) {
			for (int s : new int[]{-1, 1}) {
				fill(cx + s * half, y + h, cz - 1, cx + s * half, y + h + 4, cz + 1, Blocks.IRON_BARS);
				fill(cx - 1, y + h, cz + s * half, cx + 1, y + h + 4, cz + s * half, Blocks.IRON_BARS);
			}
		}
		fill(cx - 1, y + 1, cz + half, cx + 1, y + 4, cz + half, Blocks.AIR);
		int r = Math.max(1, half - 3);
		int per = 8 * r;
		int[] px = new int[per];
		int[] pz = new int[per];
		int n = 0;
		for (int z = cz + r; z >= cz - r; z--) {
			px[n] = cx - r;
			pz[n++] = z;
		}
		for (int x = cx - r + 1; x <= cx + r; x++) {
			px[n] = x;
			pz[n++] = cz - r;
		}
		for (int z = cz - r + 1; z <= cz + r; z++) {
			px[n] = cx + r;
			pz[n++] = z;
		}
		for (int x = cx + r - 1; x >= cx - r + 1; x--) {
			px[n] = x;
			pz[n++] = cz + r;
		}
		int base = y + 1;
		int steps = 2 * climb;
		for (int i = 0; i < steps; i++) {
			int idx = (i + rot) % n;
			int x = px[idx];
			int z = pz[idx];
			int ix = Math.abs(x - cx) == r ? x + (x < cx ? 1 : -1) : x;
			int iz = Math.abs(z - cz) == r ? z + (z < cz ? 1 : -1) : z;
			if (mirror) {
				x = 2 * cx - x;
				ix = 2 * cx - ix;
			}
			rampBlock(x, z, base, i + 1, -1);
			rampBlock(ix, iz, base, i + 1, -1);
		}
		if (withRoof) {
			roof(cx, y + height + 1, cz, half);
		}
	}

	private static void castlePart(int part) {
		switch (part) {
			case 0, 1, 2, 3 -> clearArea(-100, 140 + part * 40, 99, 179 + part * 40);
			case 4 -> castleOuterWall();
			case 5 -> castleOuterTowers();
			case 6 -> castleGatehouse();
			case 7 -> castleInnerWall();
			case 8 -> castleWardRoadAndFountains();
			case 9 -> castleWardBuildings();
			case 10 -> keepShell();
			case 11 -> hallFloor();
			case 12 -> hallColumnsAndLight();
			case 13 -> hallGalleries();
			case 14 -> throneDais();
			case 15 -> keepStairTowers();
			case 16 -> upperKeepShell();
			case 17 -> upperFloorsAndRuns();
			case 18 -> upperRooms();
			case 19 -> upperKeepTowers();
			case 20 -> greatTower();
			default -> castleDecor();
		}
	}

	private static void castleOuterWall() {
		int hw = CASTLE_HW;
		int h = 44;
		thick(-hw, CASTLE_ZA, hw, CASTLE_ZA + 4, h);
		thick(-hw, CASTLE_ZB - 4, hw, CASTLE_ZB, h);
		thick(-hw, CASTLE_ZA, -hw + 4, CASTLE_ZB, h);
		thick(hw - 4, CASTLE_ZA, hw, CASTLE_ZB, h);
		for (int z : new int[]{CASTLE_ZA, CASTLE_ZB}) {
			crenel(-hw, z, hw, z, G + h);
		}
		crenel(-hw, CASTLE_ZA, -hw, CASTLE_ZB, G + h);
		crenel(hw, CASTLE_ZA, hw, CASTLE_ZB, G + h);
		crenel(-hw, CASTLE_ZA + 4, hw, CASTLE_ZA + 4, G + h);
		crenel(-hw, CASTLE_ZB - 4, hw, CASTLE_ZB - 4, G + h);
		crenel(-hw + 4, CASTLE_ZA, -hw + 4, CASTLE_ZB, G + h);
		crenel(hw - 4, CASTLE_ZA, hw - 4, CASTLE_ZB, G + h);
		// Wandbänder und Schießscharten
		for (int x = -hw + 5; x <= hw - 5; x += 5) {
			if (Math.abs(x) > 18) {
				fill(x, G + 18, CASTLE_ZA, x, G + 24, CASTLE_ZA, Blocks.IRON_BARS);
				fill(x, G + 32, CASTLE_ZA, x, G + 36, CASTLE_ZA, Blocks.IRON_BARS);
			}
		}
		for (int x = -hw; x <= hw; x++) {
			set(x, G + 10, CASTLE_ZA, Blocks.CHISELED_POLISHED_BLACKSTONE);
			set(x, G + 28, CASTLE_ZA, Blocks.CHISELED_POLISHED_BLACKSTONE);
		}
		for (int z = CASTLE_ZA; z <= CASTLE_ZB; z++) {
			set(-hw, G + 10, z, Blocks.CHISELED_POLISHED_BLACKSTONE);
			set(hw, G + 10, z, Blocks.CHISELED_POLISHED_BLACKSTONE);
		}
		// Wehrgang-Laternen
		for (int x = -hw + 8; x <= hw - 8; x += 8) {
			lantern(x, G + h + 1, CASTLE_ZA + 3);
			lantern(x, G + h + 1, CASTLE_ZB - 3);
		}
	}

	private static void castleOuterTowers() {
		int hw = CASTLE_HW;
		for (int sx : new int[]{-1, 1}) {
			tower(sx * hw, CASTLE_ZA + 2, 10, G, 110, true);
			tower(sx * hw, CASTLE_ZB - 2, 10, G, 110, true);
			for (int z : new int[]{184, 224, 264}) {
				tower(sx * hw, z, 6, G, 70, true);
			}
			for (int x : new int[]{40, 64}) {
				tower(sx * x, CASTLE_ZA + 2, 6, G, 66, true);
			}
		}
		for (int x : new int[]{-64, -32, 0, 32, 64}) {
			tower(x, CASTLE_ZB - 2, 6, G, 70, true);
		}
	}

	private static void castleGatehouse() {
		// zwei gewaltige Torburg-Türme
		for (int s : new int[]{-1, 1}) {
			tower(s * 17, CASTLE_ZA + 2, 8, G, 82, true);
			for (int y = G + 6; y < G + 80; y += 14) {
				fill(s * 17 - 2, y, CASTLE_ZA - 1, s * 17 + 2, y + 5, CASTLE_ZA - 1, Blocks.IRON_BARS);
			}
		}
		// Torbogen und Brücke über dem Tor
		fill(-9, G, CASTLE_ZA - 1, 9, G + 50, CASTLE_ZA + 5, wall());
		fill(-8, G, CASTLE_ZA - 1, 8, G + 26, CASTLE_ZA + 5, Blocks.AIR);
		for (int dx = -8; dx <= 8; dx++) {
			int arc = (int) Math.round(Math.sqrt(Math.max(0, 64 - dx * dx)) * 0.4);
			fill(dx, G + 26, CASTLE_ZA - 1, dx, G + 26 + arc, CASTLE_ZA + 5, Blocks.AIR);
		}
		fill(-9, G + 30, CASTLE_ZA - 1, 9, G + 33, CASTLE_ZA + 5, Blocks.CHISELED_POLISHED_BLACKSTONE);
		fill(-6, G + 14, CASTLE_ZA, 6, G + 24, CASTLE_ZA, Blocks.IRON_BARS);
		for (int x = -6; x <= 6; x += 4) {
			lantern(x, G + 13, CASTLE_ZA + 2);
		}
		for (int s : new int[]{-1, 1}) {
			for (int k = 0; k < 4; k++) {
				post(s * 9, G, CASTLE_ZA - 6 - k * 5, 6);
			}
		}
		crenel(-9, CASTLE_ZA - 1, 9, CASTLE_ZA + 5, G + 50);
		for (int z = CASTLE_ZA - 10; z < CASTLE_ZA; z++) {
			for (int x = -9; x <= 9; x++) {
				set(x, G - 1, z, (Math.abs(x) <= 1) ? Blocks.AMETHYST_BLOCK : Blocks.CHISELED_POLISHED_BLACKSTONE);
			}
		}
	}

	private static void castleInnerWall() {
		int z1 = 204;
		thick(-64, z1, 64, z1 + 3, 34);
		thick(-64, z1, -61, CASTLE_ZB - 4, 34);
		thick(61, z1, 64, CASTLE_ZB - 4, 34);
		crenel(-64, z1, 64, z1, G + 34);
		crenel(-64, z1 + 3, 64, z1 + 3, G + 34);
		crenel(-64, z1, -64, CASTLE_ZB - 4, G + 34);
		crenel(64, z1, 64, CASTLE_ZB - 4, G + 34);
		for (int sx : new int[]{-1, 1}) {
			tower(sx * 64, z1, 8, G, 82, true);
			tower(sx * 64, 250, 6, G, 66, true);
			tower(sx * 64, CASTLE_ZB - 6, 7, G, 74, true);
			for (int x : new int[]{30, 46}) {
				tower(sx * x, z1 + 1, 4, G, 56, true);
			}
		}
		// Tor der Innenmauer
		fill(-8, G, z1, 8, G + 22, z1 + 3, Blocks.AIR);
		fill(-9, G + 23, z1, 9, G + 26, z1 + 3, Blocks.CHISELED_POLISHED_BLACKSTONE);
		fill(-8, G + 18, z1 + 1, 8, G + 22, z1 + 1, Blocks.IRON_BARS);
		for (int s : new int[]{-1, 1}) {
			fill(s * 62, G, 250, s * 62, G + 5, 254, Blocks.AIR);
			fill(s * 61, G, 250, s * 64, G + 5, 254, Blocks.AIR);
		}
	}

	private static void castleWardRoadAndFountains() {
		for (int z = CASTLE_ZA + 5; z <= 213; z++) {
			for (int x = -10; x <= 10; x++) {
				boolean edge = Math.abs(x) >= 9;
				boolean mid = Math.abs(x) <= 2;
				boolean bar = z % 8 == 0;
				set(x, G - 1, z, edge || bar ? Blocks.CHISELED_POLISHED_BLACKSTONE : (mid ? Blocks.AMETHYST_BLOCK : Blocks.POLISHED_BLACKSTONE_BRICKS));
			}
			if (z % 8 == 0) {
				for (int s : new int[]{-1, 1}) {
					fill(s * 12, G, z, s * 12, G + 8, z, Blocks.POLISHED_BLACKSTONE_BRICKS);
					lantern(s * 12, G + 9, z);
				}
			}
		}
		for (int z : new int[]{160, 190}) {
			for (int s : new int[]{-1, 1}) {
				int x = s * 17;
				fill(x - 2, G, z - 2, x + 2, G + 1, z + 2, Blocks.DEEPSLATE_BRICKS);
				fill(x, G + 2, z, x, G + 14, z, Blocks.POLISHED_BLACKSTONE_BRICKS);
				fill(x - 2, G + 12, z, x + 2, G + 12, z, Blocks.BLACKSTONE_WALL);
				fill(x, G + 12, z - 2, x, G + 12, z + 2, Blocks.BLACKSTONE_WALL);
				set(x, G + 15, z, Blocks.CHISELED_POLISHED_BLACKSTONE);
				set(x, G + 16, z, Blocks.CRYING_OBSIDIAN);
			}
		}
		for (int s : new int[]{-1, 1}) {
			int fx = s * 34;
			int fz = 192;
			for (int x = -9; x <= 9; x++) {
				for (int z = -9; z <= 9; z++) {
					double d = Math.sqrt(x * x + z * z);
					if (d <= 9.5 && d > 7.0) {
						fill(fx + x, G, fz + z, fx + x, G + 1, fz + z, Blocks.DEEPSLATE_BRICKS);
					} else if (d <= 7.0) {
						set(fx + x, G, fz + z, Blocks.WATER);
						set(fx + x, G - 1, fz + z, Blocks.DEEPSLATE_TILES);
					}
				}
			}
			fill(fx - 1, G + 1, fz - 1, fx + 1, G + 3, fz + 1, Blocks.DEEPSLATE_BRICKS);
			fill(fx, G + 4, fz, fx, G + 14, fz, Blocks.POLISHED_BLACKSTONE_BRICKS);
			fill(fx - 4, G + 11, fz, fx + 4, G + 11, fz, Blocks.BLACKSTONE_WALL);
			fill(fx, G + 11, fz - 4, fx, G + 11, fz + 4, Blocks.BLACKSTONE_WALL);
			set(fx, G + 15, fz, Blocks.CRYING_OBSIDIAN);
		}
	}

	private static void castleWardBuildings() {
		// Kaserne links vorne
		box(-86, G, 152, -40, G + 16, 176);
		fill(-66, G, 176, -60, G + 6, 176, Blocks.AIR);
		for (int x = -82; x <= -44; x += 6) {
			fill(x, G + 6, 152, x, G + 11, 152, Blocks.IRON_BARS);
			fill(x, G + 6, 176, x, G + 11, 176, Blocks.IRON_BARS);
		}
		fill(-85, G + 8, 153, -41, G + 8, 175, Blocks.POLISHED_BLACKSTONE_BRICKS);
		roof(-63, G + 17, 164, 12);
		// Stall rechts vorne
		box(40, G, 152, 86, G + 14, 176);
		fill(58, G, 152, 68, G + 6, 152, Blocks.AIR);
		for (int x = 44; x <= 82; x += 6) {
			fill(x, G, 160, x, G + 1, 170, Blocks.DEEPSLATE_BRICKS);
		}
		roof(63, G + 15, 164, 12);
		// Waffenkammer links hinten, hoher Glockenturm
		box(-86, G, 184, -44, G + 20, 200);
		fill(-72, G, 200, -66, G + 6, 200, Blocks.AIR);
		for (int x = -80; x <= -50; x += 8) {
			set(x, G + 1, 192, Blocks.ANVIL);
		}
		battlements(-86, 184, -44, 200, G + 20);
		stairTower(-66, 180, 4, G, 60, 56, 0, false, true);
		// Übungsplatz rechts hinten
		for (int x = 46; x <= 84; x += 6) {
			for (int z = 186; z <= 198; z += 6) {
				fill(x, G, z, x, G + 3, z, Blocks.BLACKSTONE_WALL);
				fill(x - 1, G + 2, z, x + 1, G + 2, z, Blocks.BLACKSTONE_WALL);
				lantern(x, G + 4, z);
			}
		}
		for (int x = 40; x <= 86; x += 2) {
			set(x, G, 183, Blocks.BLACKSTONE_WALL);
			set(x, G, 201, Blocks.BLACKSTONE_WALL);
		}
		// Kapelle und Ruinen im Hof
		box(-30, G, 150, -22, G + 18, 166);
		fill(-27, G, 166, -25, G + 5, 166, Blocks.AIR);
		roof(-26, G + 19, 158, 8);
		box(22, G, 150, 30, G + 18, 166);
		fill(25, G, 166, 27, G + 5, 166, Blocks.AIR);
		roof(26, G + 19, 158, 8);
	}

	private static void keepShell() {
		int top = G + 64;
		box(-54, G, 214, 54, top, 292);
		// Tor
		fill(-8, G + 1, 214, 8, G + 28, 214, Blocks.AIR);
		fill(-9, G + 29, 214, 9, G + 31, 214, Blocks.CHISELED_POLISHED_BLACKSTONE);
		for (int s : new int[]{-1, 1}) {
			fill(s * 10, G, 208, s * 10 + s, G + 40, 213, Blocks.POLISHED_BLACKSTONE_BRICKS);
			fill(s * 14, G, 208, s * 14 + s, G + 28, 213, Blocks.POLISHED_BLACKSTONE_BRICKS);
			lantern(s * 10, G + 41, 210);
		}
		fill(-12, G + 38, 208, 12, G + 41, 213, Blocks.CHISELED_POLISHED_BLACKSTONE);
		fill(-8, G + 1, 215, 8, G + 28, 215, Blocks.AIR);
		// hohe Fenster in allen Wänden
		for (int z = 224; z <= 284; z += 10) {
			for (int s : new int[]{-1, 1}) {
				fill(s * 54, G + 22, z, s * 54, G + 44, z + 2, Blocks.IRON_BARS);
				fill(s * 54, G + 48, z, s * 54, G + 60, z + 2, Blocks.IRON_BARS);
				fill(s * 54, G + 8, z, s * 54, G + 16, z + 2, Blocks.IRON_BARS);
			}
		}
		for (int x = -44; x <= 44; x += 10) {
			if (Math.abs(x) > 12) {
				fill(x, G + 22, 214, x + 2, G + 44, 214, Blocks.IRON_BARS);
				fill(x, G + 48, 214, x + 2, G + 60, 214, Blocks.IRON_BARS);
			}
		}
		crenel(-54, 214, 54, 292, top + 1);
	}

	private static void hallFloor() {
		for (int z = 215; z <= 291; z++) {
			for (int x = -53; x <= 53; x++) {
				boolean runner = Math.abs(x) <= 5;
				boolean edge = Math.abs(x) == 6;
				if (runner) {
					set(x, G, z, Blocks.AMETHYST_BLOCK);
				} else if (edge || (x + z) % 8 == 0 || (x - z) % 12 == 0) {
					set(x, G, z, Blocks.CHISELED_POLISHED_BLACKSTONE);
				} else {
					set(x, G, z, Blocks.POLISHED_BLACKSTONE_BRICKS);
				}
			}
		}
	}

	private static void hallColumnsAndLight() {
		int top = G + 63;
		for (int x : new int[]{-36, -18, 18, 36}) {
			for (int z = 226; z <= 286; z += 10) {
				fill(x - 2, G + 1, z - 2, x + 2, G + 3, z + 2, Blocks.DEEPSLATE_BRICKS);
				fill(x - 1, G + 4, z - 1, x + 1, top - 6, z + 1, Blocks.POLISHED_BLACKSTONE_BRICKS);
				fill(x - 2, top - 5, z - 2, x + 2, top - 3, z + 2, Blocks.CHISELED_POLISHED_BLACKSTONE);
				fill(x - 3, top - 2, z - 3, x + 3, top, z + 3, Blocks.DEEPSLATE_BRICKS);
				for (int y = G + 12; y < top - 8; y += 12) {
					fill(x - 2, y, z - 2, x + 2, y, z + 2, Blocks.CHISELED_POLISHED_BLACKSTONE);
					lantern(x + 3, y, z);
					lantern(x - 3, y, z);
				}
			}
		}
		// Längsbalken und Bögen
		for (int x : new int[]{-36, -18, 18, 36}) {
			fill(x - 1, top - 8, 226, x + 1, top - 6, 286, Blocks.POLISHED_BLACKSTONE_BRICKS);
		}
		for (int z = 226; z <= 286; z += 10) {
			fill(-36, top - 8, z, 36, top - 6, z, Blocks.POLISHED_BLACKSTONE_BRICKS);
		}
		// Banner aus Amethyst, 30 hoch, an den Seitenwänden
		for (int z = 220; z <= 286; z += 10) {
			for (int s : new int[]{-1, 1}) {
				fill(s * 53, G + 12, z + 3, s * 53, G + 46, z + 5, Blocks.AMETHYST_BLOCK);
				fill(s * 53, G + 11, z + 3, s * 53, G + 11, z + 5, Blocks.CRYING_OBSIDIAN);
				fill(s * 53, G + 47, z + 3, s * 53, G + 47, z + 5, Blocks.CHISELED_POLISHED_BLACKSTONE);
				fill(s * 53, G + 28, z + 4, s * 53, G + 36, z + 4, Blocks.CRYING_OBSIDIAN);
			}
		}
		// Kronleuchter: Ketten aus Eisengittern mit Laternenring
		for (int z = 224; z <= 284; z += 20) {
			for (int x : new int[]{-27, 0, 27}) {
				fill(x, G + 44, z, x, top - 1, z, Blocks.IRON_BARS);
				fill(x - 3, G + 43, z, x + 3, G + 43, z, Blocks.IRON_BARS);
				fill(x, G + 43, z - 3, x, G + 43, z + 3, Blocks.IRON_BARS);
				for (int[] o : new int[][]{{-3, 0}, {3, 0}, {0, -3}, {0, 3}, {0, 0}}) {
					lantern(x + o[0], G + 42, z + o[1]);
				}
			}
		}
		// Feuerschalen entlang des Läufers
		for (int z = 220; z <= 240; z += 6) {
			for (int s : new int[]{-1, 1}) {
				fill(s * 8, G + 1, z, s * 8, G + 4, z, Blocks.POLISHED_BLACKSTONE_BRICKS);
				fill(s * 8 - 1, G + 5, z - 1, s * 8 + 1, G + 5, z + 1, Blocks.DEEPSLATE_TILES);
				set(s * 8, G + 6, z, Blocks.SOUL_CAMPFIRE);
			}
		}
	}

	private static void hallGalleries() {
		for (int s : new int[]{-1, 1}) {
			int x1 = s < 0 ? -52 : 47;
			int x2 = s < 0 ? -47 : 52;
			int inner = s < 0 ? -47 : 47;
			int gy = G + 12;
			fill(x1, gy, 250, x2, gy, 284, Blocks.POLISHED_BLACKSTONE_BRICKS);
			fill(inner, gy + 1, 250, inner, gy + 1, 284, Blocks.IRON_BARS);
			for (int z = 250; z <= 284; z += 6) {
				fill(inner, G + 1, z, inner, gy - 1, z, Blocks.POLISHED_BLACKSTONE_BRICKS);
				fill(inner + s * -1, gy + 2, z, inner + s * -1, gy + 3, z, Blocks.POLISHED_BLACKSTONE_BRICKS);
				lantern(inner, gy + 2, z);
			}
			// Aufgang mit Halbstufen: 24 Blöcke lang, 12 hoch
			for (int n = 0; n < 24; n++) {
				for (int x = x1; x <= x2; x++) {
					rampBlock(x, 226 + n, G + 1, n + 1, G + 1);
				}
			}
		}
	}

	private static void throneDais() {
		// breite Vorstufe
		fill(-40, G + 1, 226, 40, G + 1, 235, Blocks.DEEPSLATE_BRICKS);
		fill(-30, G + 2, 230, 30, G + 2, 235, Blocks.DEEPSLATE_BRICKS);
		// Rampe: 48 Blöcke lang, 24 hoch, mit Halbstufen
		for (int n = 0; n < 48; n++) {
			int z = 236 + n;
			for (int x = -12; x <= 12; x++) {
				rampBlock(x, z, G + 1, n + 1, G + 1);
			}
			int top = G + 1 + (n + 1) / 2;
			for (int s : new int[]{-1, 1}) {
				fill(s * 13, G + 1, z, s * 13, top + 2, z, Blocks.POLISHED_BLACKSTONE_BRICKS);
				if (n % 8 == 3) {
					fill(s * 13, top + 3, z, s * 13, top + 5, z, Blocks.POLISHED_BLACKSTONE_BRICKS);
					lantern(s * 13, top + 6, z);
				}
			}
			for (int x : new int[]{-1, 0, 1}) {
				if (n % 2 == 1) {
					set(x, G + 1 + (n + 1) / 2 - 1, z, Blocks.AMETHYST_BLOCK);
				}
			}
		}
		int py = G + 24;
		// Plattform, 24 Blöcke über der Halle
		fill(-18, G + 1, 284, 18, py, 291, Blocks.DEEPSLATE_BRICKS);
		fill(-18, py, 284, 18, py, 291, Blocks.CHISELED_POLISHED_BLACKSTONE);
		fill(-6, py, 284, 6, py, 291, Blocks.AMETHYST_BLOCK);
		for (int s : new int[]{-1, 1}) {
			fill(s * 18, py + 1, 284, s * 18, py + 3, 291, Blocks.BLACKSTONE_WALL);
			for (int z : new int[]{285, 290}) {
				fill(s * 17, py + 1, z, s * 17, py + 14, z, Blocks.POLISHED_BLACKSTONE_BRICKS);
				fill(s * 17 - 1, py + 15, z - 1, s * 17 + 1, py + 15, z + 1, Blocks.DEEPSLATE_TILES);
				set(s * 17, py + 16, z, Blocks.SOUL_CAMPFIRE);
			}
		}
		// Thron: Sitz, Armlehnen, 24 Blöcke hohe Lehne mit Krone
		fill(-2, py + 1, 287, 2, py + 1, 290, Blocks.AMETHYST_BLOCK);
		fill(-3, py + 1, 287, -3, py + 3, 290, Blocks.CHISELED_POLISHED_BLACKSTONE);
		fill(3, py + 1, 287, 3, py + 3, 290, Blocks.CHISELED_POLISHED_BLACKSTONE);
		fill(-3, py + 1, 290, 3, py + 22, 290, Blocks.CHISELED_POLISHED_BLACKSTONE);
		fill(-2, py + 2, 290, 2, py + 20, 290, Blocks.AMETHYST_BLOCK);
		fill(-4, py + 20, 290, 4, py + 22, 290, Blocks.CHISELED_POLISHED_BLACKSTONE);
		for (int x = -4; x <= 4; x += 2) {
			fill(x, py + 23, 290, x, py + 23 + (x == 0 ? 6 : 3 - Math.abs(x) / 2), 290, Blocks.POLISHED_BLACKSTONE_BRICKS);
			set(x, py + 24 + (x == 0 ? 6 : 3 - Math.abs(x) / 2), 290, Blocks.CRYING_OBSIDIAN);
		}
		fill(-1, py + 3, 290, 1, py + 18, 290, Blocks.CRYING_OBSIDIAN);
		fill(0, py + 3, 290, 0, py + 18, 290, Blocks.AMETHYST_BLOCK);
		// Rückwand: riesiges Amethyst-Fenster
		fill(-14, py + 1, 291, 14, py + 40, 291, Blocks.CHISELED_POLISHED_BLACKSTONE);
		fill(-12, py + 3, 291, 12, py + 38, 291, Blocks.AMETHYST_BLOCK);
		fill(0, py + 3, 291, 0, py + 38, 291, Blocks.CRYING_OBSIDIAN);
		fill(-12, py + 20, 291, 12, py + 20, 291, Blocks.CRYING_OBSIDIAN);
		// Flankierende Pfeiler
		for (int s : new int[]{-1, 1}) {
			fill(s * 22, py + 1, 288, s * 22, G + 62, 288, Blocks.POLISHED_BLACKSTONE_BRICKS);
			fill(s * 22 - 1, py + 20, 287, s * 22 + 1, py + 20, 289, Blocks.CHISELED_POLISHED_BLACKSTONE);
		}
	}

	private static void keepStairTowers() {
		for (int sx : new int[]{-1, 1}) {
			stairTower(sx * 54, 214, 9, G, 102, 64, 4, sx > 0, true);
			stairTower(sx * 54, 292, 9, G, 102, 64, 4, sx > 0, true);
		}
		// Hallentüren zu den vorderen Treppentürmen
		for (int sx : new int[]{-1, 1}) {
			fill(sx * 54 - 1, G + 1, 223, sx * 54 + 1, G + 4, 223, Blocks.AIR);
			// Ausgang zum Dach des Hauptgebäudes auf Höhe G+64
			fill(sx * 45, G + 66, 218, sx * 45, G + 69, 220, Blocks.AIR);
		}
		// Eingang bleibt offen
		fill(-8, G + 1, 214, 8, G + 28, 215, Blocks.AIR);
	}

	// ------------------------------------------------------------------ Oberes Schloss (G+64 .. G+112)

	private static final int UPPER_Z1 = 216;
	private static final int UPPER_Z2 = 286;

	private static void upperKeepShell() {
		int y1 = G + 64;
		int y2 = G + 112;
		box(-40, y1, UPPER_Z1, 40, y2, UPPER_Z2);
		// Türen zum Dach-Weg, auf Höhe der Treppentürme
		for (int s : new int[]{-1, 1}) {
			fill(s * 40, y1 + 1, 218, s * 40, y1 + 4, 220, Blocks.AIR);
		}
		// Fenster, Gesimse
		for (int y = y1 + 6; y < y2 - 6; y += 12) {
			for (int z = 222; z <= 280; z += 8) {
				for (int s : new int[]{-1, 1}) {
					fill(s * 40, y, z, s * 40, y + 5, z + 2, Blocks.IRON_BARS);
				}
			}
			for (int x = -34; x <= 34; x += 8) {
				fill(x, y, UPPER_Z1, x + 2, y + 5, UPPER_Z1, Blocks.IRON_BARS);
				fill(x, y, UPPER_Z2, x + 2, y + 5, UPPER_Z2, Blocks.IRON_BARS);
			}
		}
		for (int y : new int[]{y1 + 2, y1 + 26, y1 + 50}) {
			for (int x = -40; x <= 40; x++) {
				set(x, y, UPPER_Z1 - 1, Blocks.CHISELED_POLISHED_BLACKSTONE);
				set(x, y, UPPER_Z2 + 1, Blocks.CHISELED_POLISHED_BLACKSTONE);
			}
		}
		crenel(-40, UPPER_Z1, 40, UPPER_Z2, y2 + 1);
	}

	private static void upperFloorsAndRuns() {
		int[] floors = {G + 76, G + 88, G + 100};
		for (int f : floors) {
			fill(-39, f, UPPER_Z1 + 1, 39, f, UPPER_Z2 - 1, Blocks.POLISHED_BLACKSTONE_BRICKS);
		}
		// Läufer aus Amethyst in jedem Stockwerk
		for (int f : new int[]{G + 64, G + 76, G + 88, G + 100}) {
			fill(-2, f, UPPER_Z1 + 1, 2, f, UPPER_Z2 - 1, Blocks.AMETHYST_BLOCK);
		}
		// Rampen von Stockwerk zu Stockwerk
		int[] levels = {G + 64, G + 76, G + 88, G + 100, G + 112};
		for (int t = 0; t < 4; t++) {
			int x1 = (t % 2 == 0) ? -39 : 37;
			int x2 = x1 + 2;
			int base = levels[t] + 1;
			// Loch im darüberliegenden Boden
			fill(x1, levels[t + 1], 226, x2, levels[t + 1], 249, Blocks.AIR);
			for (int n = 0; n < 24; n++) {
				for (int x = x1; x <= x2; x++) {
					rampBlock(x, 226 + n, base, n + 1, -1);
				}
			}
		}
	}

	private static void upperRooms() {
		// 1. Stockwerk: Königsgemächer; 2.: Bibliothek; 3.: Waffensaal; 4.: Sternwarte
		for (int f = 0; f < 4; f++) {
			int y = G + 64 + f * 12;
			for (int z = 224; z <= 280; z += 14) {
				for (int s : new int[]{-1, 1}) {
					fill(s * 22, y + 1, z, s * 22, y + 10, z, Blocks.POLISHED_BLACKSTONE_BRICKS);
					lantern(s * 22 + 1, y + 6, z);
					lantern(s * 22 - 1, y + 6, z);
				}
			}
			for (int z = 224; z <= 280; z += 14) {
				lantern(0, y + 10, z);
			}
			for (int s : new int[]{-1, 1}) {
				if (f == 1) {
					fill(s * 38, y + 1, 252, s * 38, y + 9, UPPER_Z2 - 8, Blocks.BOOKSHELF);
				}
			}
			if (f == 0) {
				for (int s : new int[]{-1, 1}) {
					fill(s * 30, y + 1, 270, s * 34, y + 1, 272, Blocks.DEEPSLATE_BRICKS);
					fill(s * 30, y + 2, 270, s * 34, y + 2, 270, Blocks.BLACKSTONE_SLAB);
					set(s * 32, y + 3, 271, Blocks.SOUL_LANTERN);
				}
				fill(-8, y + 1, 260, 8, y + 2, 262, Blocks.DEEPSLATE_BRICKS);
				fill(-3, y + 3, 261, 3, y + 3, 261, Blocks.BLACKSTONE_SLAB);
			}
			if (f == 2) {
				for (int x = -34; x <= 34; x += 6) {
					if (Math.abs(x) > 4) {
						set(x, y + 1, 280, Blocks.ANVIL);
						fill(x, y + 1, 284, x, y + 5, 284, Blocks.IRON_BARS);
					}
				}
			}
			if (f == 3) {
				for (int x = -30; x <= 30; x += 10) {
					set(x, y + 1, 250, Blocks.SOUL_CAMPFIRE);
				}
			}
		}
	}

	private static void upperKeepTowers() {
		for (int sx : new int[]{-1, 1}) {
			tower(sx * 40, UPPER_Z2, 7, G + 64, 66, true);
		}
	}

	private static void greatTower() {
		// der große Mittelturm ganz oben: Wendeltreppe bis zur Spitze
		stairTower(0, 253, 14, G + 112, 60, 56, 0, false, true);
		for (int s : new int[]{-1, 1}) {
			tower(s * 30, 253, 6, G + 112, 40, true);
		}
		// Rampe vom Hallendach zum Turm: Tür nach Süden liegt bei z=267
		for (int z = 262; z <= 268; z++) {
			for (int x = -2; x <= 2; x++) {
				set(x, G + 112, z, Blocks.AMETHYST_BLOCK);
			}
		}
		for (int s : new int[]{-1, 1}) {
			for (int z : new int[]{262, 268}) {
				post(s * 3, G + 113, z, 4);
			}
		}
		// Spitzenzier auf den Zinnen
		for (int x = -36; x <= 36; x += 12) {
			post(x, G + 113, UPPER_Z1, 3);
			post(x, G + 113, UPPER_Z2, 3);
		}
	}

	private static void castleDecor() {
		scatter(-88, 208, -66, 290, 50);
		scatter(66, 208, 88, 290, 50);
		scatter(-38, 150, -30, 200, 10);
		scatter(30, 150, 38, 200, 10);
		for (int s : new int[]{-1, 1}) {
			for (int z = 214; z <= 286; z += 9) {
				int x = s * 76;
				fill(x, G, z, x, G + 3, z, Blocks.DEEPSLATE);
				set(x + s, G, z, Blocks.COBBLED_DEEPSLATE_SLAB);
			}
		}
		// Lichtpfeiler vor dem Haupttor
		for (int s : new int[]{-1, 1}) {
			for (int z = 150; z <= 200; z += 10) {
				fill(s * 24, G, z, s * 24, G + 6, z, Blocks.POLISHED_BLACKSTONE_BRICKS);
				lantern(s * 24, G + 7, z);
			}
		}
	}

	// ================================================================== Schwarzmarkt

	private static void marketPart(int part) {
		int cx = -120;
		int cz = -60;
		switch (part) {
			case 0 -> {
				clearArea(-180, -100, -61, -61);
				clearArea(-180, -60, -61, -21);
			}
			case 1 -> {
				// Außenmauer mit vier Toren
				thick(-178, -98, -63, -97, 7);
				thick(-178, -24, -63, -23, 7);
				thick(-178, -98, -177, -23, 7);
				thick(-64, -98, -63, -23, 7);
				for (int s : new int[]{-1, 1}) {
					fill(cx + s * 3, G, -98, cx + s * 3 + s * 0, G + 4, -97, Blocks.AIR);
				}
				fill(cx - 3, G, -98, cx + 3, G + 5, -97, Blocks.AIR);
				fill(cx - 3, G, -24, cx + 3, G + 5, -23, Blocks.AIR);
				fill(-178, G, cz - 3, -177, G + 5, cz + 3, Blocks.AIR);
				fill(-64, G, cz - 3, -63, G + 5, cz + 3, Blocks.AIR);
				crenel(-178, -98, -63, -98, G + 7);
				crenel(-178, -23, -63, -23, G + 7);
				crenel(-178, -98, -178, -23, G + 7);
				crenel(-63, -98, -63, -23, G + 7);
				tower(-178, -98, 5, G, 24, true);
				tower(-63, -98, 5, G, 24, true);
				tower(-178, -23, 5, G, 24, true);
				tower(-63, -23, 5, G, 24, true);
				for (int s : new int[]{-1, 1}) {
					tower(cx + s * 8, -23, 3, G, 14, true);
					tower(-63, cz + s * 8, 3, G, 14, true);
				}
				fill(cx - 3, G, -24, cx + 3, G + 5, -23, Blocks.AIR);
				fill(-64, G, cz - 3, -63, G + 5, cz + 3, Blocks.AIR);
			}
			case 2 -> {
				// Platz mit Brunnen und Auktionshaus
				for (int x = -18; x <= 18; x++) {
					for (int z = -18; z <= 18; z++) {
						double d = Math.sqrt(x * x + z * z);
						if (d <= 7.5 && d > 5.5) {
							fill(cx + x, G, cz + z, cx + x, G + 1, cz + z, Blocks.DEEPSLATE_BRICKS);
						} else if (d <= 5.5) {
							set(cx + x, G, cz + z, Blocks.WATER);
							set(cx + x, G - 1, cz + z, Blocks.DEEPSLATE_TILES);
						} else if (d <= 17.0 && ((x + z) & 1) == 0) {
							set(cx + x, G - 1, cz + z, Blocks.CHISELED_POLISHED_BLACKSTONE);
						}
					}
				}
				fill(cx, G + 1, cz, cx, G + 10, cz, Blocks.POLISHED_BLACKSTONE_BRICKS);
				set(cx, G + 11, cz, Blocks.CRYING_OBSIDIAN);
				// Auktionshaus mit Bühne
				box(cx - 20, G, -96, cx + 20, G + 16, -78);
				fill(cx - 3, G, -78, cx + 3, G + 5, -78, Blocks.AIR);
				fill(cx - 12, G + 1, -95, cx + 12, G + 2, -90, Blocks.DEEPSLATE_BRICKS);
				for (int x = cx - 16; x <= cx + 16; x += 4) {
					fill(x, G + 3, -80, x, G + 6, -80, Blocks.POLISHED_BLACKSTONE_BRICKS);
					lantern(x, G + 7, -80);
				}
				for (int x = cx - 18; x <= cx + 18; x += 6) {
					fill(x, G + 6, -96, x, G + 10, -96, Blocks.IRON_BARS);
				}
				fill(cx - 20, G + 16, -96, cx + 20, G + 17, -96, Blocks.CHISELED_POLISHED_BLACKSTONE);
				roof(cx, G + 17, -87, 12);
				for (int s : new int[]{-1, 1}) {
					tower(cx + s * 24, -87, 3, G, 20, true);
				}
			}
			default -> {
				// Marktstände in Reihen
				for (int row = 0; row < 2; row++) {
					int z0 = row == 0 ? -74 : -44;
					for (int x = -172; x <= -70; x += 9) {
						double dx = x - cx;
						double dz = z0 + 2 - cz;
						if (Math.sqrt(dx * dx + dz * dz) < 24) {
							continue;
						}
						stall(x, z0, row);
					}
				}
				for (int x = -170; x <= -70; x += 12) {
					if (Math.abs(x - cx) > 22) {
						post(x, G, cz, 4);
					}
				}
				for (int x = -176; x <= -66; x++) {
					for (int z : new int[]{-60}) {
						if (Math.abs(x - cx) > 18) {
							set(x, G - 1, z, Blocks.CHISELED_POLISHED_BLACKSTONE);
							set(x, G - 1, z + 1, Blocks.POLISHED_BLACKSTONE_BRICKS);
						}
					}
				}
				scatter(-176, -96, -150, -28, 12);
				scatter(-90, -96, -66, -28, 12);
			}
		}
	}

	private static void stall(int x, int z, int row) {
		Block roofB = (x / 9 + row) % 2 == 0 ? Blocks.AMETHYST_BLOCK : Blocks.DEEPSLATE_TILES;
		for (int dx : new int[]{0, 5}) {
			for (int dz : new int[]{0, 4}) {
				fill(x + dx, G, z + dz, x + dx, G + 3, z + dz, Blocks.POLISHED_BLACKSTONE_BRICKS);
			}
		}
		fill(x, G + 4, z, x + 5, G + 4, z + 4, roofB);
		fill(x, G, z + (row == 0 ? 4 : 0), x + 5, G, z + (row == 0 ? 4 : 0), Blocks.DEEPSLATE_BRICKS);
		set(x + 2, G + 1, z + (row == 0 ? 4 : 0), Blocks.BLACKSTONE_SLAB);
		lantern(x + 2, G + 3, z + 2);
	}

	// ================================================================== Gefängnis

	private static void prisonPart(int part) {
		int cx = 120;
		int cz = -60;
		switch (part) {
			case 0 -> {
				clearArea(60, -100, 179, -61);
				clearArea(60, -60, 179, -21);
			}
			case 1 -> {
				thick(62, -98, 177, -95, 20);
				thick(62, -26, 177, -23, 20);
				thick(62, -98, 65, -23, 20);
				thick(174, -98, 177, -23, 20);
				crenel(62, -98, 177, -98, G + 20);
				crenel(62, -23, 177, -23, G + 20);
				crenel(62, -98, 62, -23, G + 20);
				crenel(177, -98, 177, -23, G + 20);
				fill(cx - 4, G, -26, cx + 4, G + 11, -23, Blocks.AIR);
				fill(cx - 4, G + 12, -26, cx + 4, G + 14, -23, Blocks.CHISELED_POLISHED_BLACKSTONE);
				fill(cx - 4, G + 8, -25, cx + 4, G + 11, -25, Blocks.IRON_BARS);
				for (int sx : new int[]{62, 177}) {
					for (int sz : new int[]{-98, -23}) {
						tower(sx, sz, 5, G, 34, true);
					}
				}
				tower(62, -60, 4, G, 28, true);
				tower(177, -60, 4, G, 28, true);
				tower(cx, -98, 4, G, 28, true);
				for (int s : new int[]{-1, 1}) {
					tower(cx + s * 9, -23, 4, G, 28, true);
				}
				fill(cx - 4, G, -26, cx + 4, G + 11, -23, Blocks.AIR);
				fill(cx - 4, G + 8, -25, cx + 4, G + 11, -25, Blocks.IRON_BARS);
			}
			case 2 -> {
				cellBlock(68, -92, 108, -76);
				cellBlock(132, -92, 172, -76);
			}
			case 3 -> {
				tower(cx, cz, 6, G, 40, true);
				for (int z = -75; z <= -27; z += 6) {
					set(cx - 8, G, z, Blocks.IRON_BARS);
					set(cx + 8, G, z, Blocks.IRON_BARS);
				}
				gallows(152, -50);
				// Wachkaserne
				box(68, G, -50, 100, G + 12, -34);
				fill(82, G, -34, 86, G + 4, -34, Blocks.AIR);
				for (int x = 72; x <= 96; x += 6) {
					fill(x, G + 4, -50, x, G + 7, -50, Blocks.IRON_BARS);
				}
				battlements(68, -50, 100, -34, G + 12);
			}
			default -> {
				tower(168, -32, 4, G, 40, true);
				for (int y = G + 3; y < G + 40; y += 6) {
					for (int s : new int[]{-1, 1}) {
						set(168 + s * 4, y, -32, Blocks.IRON_BARS);
					}
				}
				// Hof: Pflaster, Feuerstellen, Wachposten
				for (int x = 66; x <= 173; x++) {
					for (int z = -74; z <= -27; z++) {
						if ((x + z) % 6 == 0 && Math.abs(x - cx) > 10) {
							set(x, G - 1, z, Blocks.CHISELED_POLISHED_BLACKSTONE);
						}
					}
				}
				for (int x : new int[]{80, 104, 136, 160}) {
					post(x, G, -66, 4);
					set(x, G, -64, Blocks.SOUL_CAMPFIRE);
				}
				scatter(66, -74, 112, -52, 6);
				scatter(128, -74, 172, -62, 6);
			}
		}
	}

	private static void cellBlock(int x1, int z1, int x2, int z2) {
		int cz = (z1 + z2) / 2;
		box(x1, G, z1, x2, G + 14, z2);
		fill(x1 + 1, G + 7, z1 + 1, x2 - 1, G + 7, z2 - 1, Blocks.POLISHED_BLACKSTONE_BRICKS);
		// Treppe im Mittelgang
		for (int k = 0; k < 7; k++) {
			fill(x1 + 1 + k, G + 1, cz, x1 + 1 + k, G + 1 + k, cz, Blocks.DEEPSLATE_BRICKS);
		}
		fill(x1 + 2, G + 7, cz, x1 + 7, G + 7, cz, Blocks.AIR);
		for (int floor = 0; floor < 2; floor++) {
			int y = G + 1 + floor * 7;
			for (int x = x1 + 1; x < x2; x += 5) {
				if (x > x1 + 1) {
					fill(x, y, z1 + 1, x, y + 4, cz - 2, Blocks.BLACKSTONE);
					fill(x, y, cz + 2, x, y + 4, z2 - 1, Blocks.BLACKSTONE);
				}
			}
			for (int x = x1 + 1; x < x2; x++) {
				boolean door = (x - x1 - 1) % 5 == 2;
				if (!door) {
					fill(x, y, cz - 1, x, y + 4, cz - 1, Blocks.IRON_BARS);
					fill(x, y, cz + 1, x, y + 4, cz + 1, Blocks.IRON_BARS);
				} else {
					fill(x, y + 3, cz - 1, x, y + 4, cz - 1, Blocks.IRON_BARS);
					fill(x, y + 3, cz + 1, x, y + 4, cz + 1, Blocks.IRON_BARS);
				}
			}
			for (int x = x1 + 3; x < x2; x += 5) {
				set(x, y + 1, z1 + 1, Blocks.BLACKSTONE_SLAB);
				set(x, y + 1, z2 - 1, Blocks.BLACKSTONE_SLAB);
				set(x, y + 2, z1, Blocks.IRON_BARS);
				set(x, y + 2, z2, Blocks.IRON_BARS);
			}
			for (int x = x1 + 10; x < x2; x += 10) {
				lantern(x, y + 4, cz);
			}
		}
		fill(x2, G + 1, cz, x2, G + 3, cz, Blocks.AIR);
		battlements(x1, z1, x2, z2, G + 15);
	}

	// ================================================================== Schattentempel

	private static void templePart(int part) {
		int cx = 0;
		int cz = -80;
		switch (part) {
			case 0 -> {
				clearArea(-60, -140, 59, -101);
				clearArea(-60, -100, 59, -61);
				clearArea(-60, -60, 59, -21);
			}
			case 1 -> {
				for (int k = 0; k < 5; k++) {
					int half = 40 - 8 * k;
					Block b = k % 2 == 0 ? Blocks.DEEPSLATE_BRICKS : Blocks.POLISHED_BLACKSTONE_BRICKS;
					fill(cx - half, G, cz - half, cx + half, G + 4 * k + 3, cz + half, b);
					fill(cx - half, G + 4 * k + 3, cz - half, cx + half, G + 4 * k + 3, cz + half, Blocks.CHISELED_POLISHED_BLACKSTONE);
					fill(cx - half + 1, G + 4 * k + 3, cz - half + 1, cx + half - 1, G + 4 * k + 3, cz + half - 1, Blocks.DEEPSLATE_TILES);
					for (int s : new int[]{-1, 1}) {
						for (int t : new int[]{-1, 1}) {
							set(cx + s * half, G + 4 * k + 4, cz + t * half, Blocks.SOUL_CAMPFIRE);
						}
					}
				}
			}
			case 2 -> {
				// Treppen in alle vier Richtungen: Anlauf vom Boden, dann je Ring eine Rampe
				for (int e = 1; e <= 4; e++) {
					int d = 40 + e;
					int top = G + (4 - e);
					for (int w = -3; w <= 3; w++) {
						fill(cx + w, G, cz + d, cx + w, top, cz + d, Blocks.DEEPSLATE_TILES);
						fill(cx + w, G, cz - d, cx + w, top, cz - d, Blocks.DEEPSLATE_TILES);
						fill(cx + d, G, cz + w, cx + d, top, cz + w, Blocks.DEEPSLATE_TILES);
						fill(cx - d, G, cz + w, cx - d, top, cz + w, Blocks.DEEPSLATE_TILES);
					}
				}
				for (int k = 0; k < 4; k++) {
					int half = 40 - 8 * k;
					for (int i = 0; i < 8; i++) {
						int d = half - i;
						int top = G + 4 * k + 3 + (i + 2) / 2;
						for (int w = -3; w <= 3; w++) {
							Block b = Math.abs(w) == 3 ? Blocks.CHISELED_POLISHED_BLACKSTONE : Blocks.DEEPSLATE_TILES;
							fill(cx + w, G, cz + d, cx + w, top, cz + d, b);
							fill(cx + w, G, cz - d, cx + w, top, cz - d, b);
							fill(cx + d, G, cz + w, cx + d, top, cz + w, b);
							fill(cx - d, G, cz + w, cx - d, top, cz + w, b);
						}
						if (i == 0) {
							for (int s : new int[]{-4, 4}) {
								int y = G + 4 * k + 4;
								post(cx + s, y, cz + d, 2);
								post(cx + s, y, cz - d, 2);
								post(cx + d, y, cz + s, 2);
								post(cx - d, y, cz + s, 2);
							}
						}
					}
				}
			}
			default -> {
				// Tempel oben, Obelisk, Säulengang zum Spawn
				int top = G + 20;
				box(cx - 6, top, cz - 6, cx + 6, top + 11, cz + 6);
				fill(cx - 1, top + 1, cz + 6, cx + 1, top + 4, cz + 6, Blocks.AIR);
				fill(cx - 5, top, cz - 5, cx + 5, top, cz + 5, Blocks.AMETHYST_BLOCK);
				for (int s : new int[]{-1, 1}) {
					for (int t : new int[]{-1, 1}) {
						fill(cx + s * 8, top, cz + t * 8, cx + s * 8, top + 9, cz + t * 8, Blocks.POLISHED_BLACKSTONE_BRICKS);
						lantern(cx + s * 8, top + 10, cz + t * 8);
					}
				}
				roof(cx, top + 12, cz, 6);
				fill(cx, top + 1, cz, cx, top + 3, cz, Blocks.CRYING_OBSIDIAN);
				set(cx, top + 4, cz, Blocks.SOUL_CAMPFIRE);
				for (int z = -38; z <= -22; z += 4) {
					for (int s : new int[]{-1, 1}) {
						fill(s * 12, G, z, s * 12, G + 9, z, Blocks.POLISHED_BLACKSTONE_BRICKS);
						set(s * 12, G + 10, z, Blocks.CHISELED_POLISHED_BLACKSTONE);
					}
				}
				fill(-12, G + 11, -38, 12, G + 11, -38, Blocks.CHISELED_POLISHED_BLACKSTONE);
				fill(-12, G + 11, -22, 12, G + 11, -22, Blocks.CHISELED_POLISHED_BLACKSTONE);
				for (int s : new int[]{-1, 1}) {
					fill(s * 12, G + 11, -38, s * 12, G + 11, -22, Blocks.CHISELED_POLISHED_BLACKSTONE);
				}
				for (int s : new int[]{-1, 1}) {
					for (int t : new int[]{-1, 1}) {
						int ox = s * 52;
						int oz = -80 + t * 52;
						fill(ox, G, oz, ox, G + 18, oz, Blocks.POLISHED_BLACKSTONE_BRICKS);
						fill(ox - 1, G, oz - 1, ox + 1, G, oz + 1, Blocks.DEEPSLATE_TILES);
						set(ox, G + 19, oz, Blocks.CRYING_OBSIDIAN);
					}
				}
				scatter(-58, -138, 58, -122, 20);
			}
		}
	}

	// ================================================================== Weltmauer und Ödland

	private static void wallPart(int part) {
		if (part == 4) {
			for (int n = 0; n < 24; n++) {
				double a = n * Math.PI * 2.0 / 24.0;
				int x = (int) Math.round(Math.cos(a) * 347);
				int z = (int) Math.round(Math.sin(a) * 347);
				tower(x, z, 6, G, 140, true);
			}
			return;
		}
		int sx = (part == 0 || part == 3) ? 1 : -1;
		int sz = (part == 0 || part == 1) ? 1 : -1;
		for (int ax = 0; ax <= 350; ax++) {
			for (int az = 0; az <= 350; az++) {
				double r = Math.sqrt((double) ax * ax + (double) az * az);
				int x = sx * ax;
				int z = sz * az;
				if (r >= 345 && r < 349) {
					for (int y = G; y < G + 90; y++) {
						set(x, y, z, wall());
					}
					if (((x + z) & 1) == 0) {
						set(x, G + 90, z, Blocks.POLISHED_BLACKSTONE_BRICKS);
					}
					if (r < 346 && (x + z) % 7 == 0) {
						fill(x, G + 40, z, x, G + 46, z, Blocks.IRON_BARS);
					}
				} else if (r >= 335 && r < 337) {
					set(x, G - 1, z, ((x + z) & 3) == 0 ? Blocks.CHISELED_POLISHED_BLACKSTONE : Blocks.POLISHED_BLACKSTONE_BRICKS);
					if ((x * 7 + z * 3) % 40 == 0) {
						post(x, G, z, 4);
					}
				}
			}
		}
	}

	private static void waste() {
		Random r = new Random(99L);
		for (int n = 0; n < 900; n++) {
			double a = r.nextDouble() * Math.PI * 2.0;
			double dist = 300 + r.nextDouble() * 34;
			int x = (int) Math.round(Math.cos(a) * dist);
			int z = (int) Math.round(Math.sin(a) * dist);
			random = r;
			scatter(x - 1, z - 1, x + 1, z + 1, 1);
		}
	}

	// ================================================================== Kathedrale (Portal zur Prachtstraße)

	private static int xs(int n) {
		return CATHEDRAL_X + 30 - n;
	}

	private static void cathedral() {
		int cx = CATHEDRAL_X;
		int cz = CATHEDRAL_Z;
		clearArea(cx - 40, cz - 40, cx + 39, cz + 39);
		scatter(cx - 38, cz - 38, cx - 33, cz + 37, 14);
		scatter(cx + 34, cz - 38, cx + 37, cz + 37, 6);
		scatter(cx - 32, cz - 38, cx + 33, cz - 14, 25);
		scatter(cx - 32, cz + 14, cx + 33, cz + 37, 25);

		int wallH = 22;
		int z1 = cz - 10;
		int z2 = cz + 10;
		int xf = xs(0);
		int xb = xs(60);

		// Hauptschiff
		shell(xb, G, z1, xf, G + wallH, z2);
		fill(xb + 1, G - 1, z1 + 1, xf - 1, G - 1, z2 - 1, Blocks.POLISHED_BLACKSTONE_BRICKS);
		for (int k = 0; k <= 10; k++) {
			for (int x = xb; x <= xf; x++) {
				set(x, G + wallH + 1 + k, z1 + k, Blocks.BLACKSTONE);
				set(x, G + wallH + 1 + k, z2 - k, Blocks.BLACKSTONE);
			}
			fill(xf, G + wallH + 1 + k, z1 + k, xf, G + wallH + 1 + k, z2 - k, wall());
			fill(xb, G + wallH + 1 + k, z1 + k, xb, G + wallH + 1 + k, z2 - k, wall());
		}
		// Portal zur Straße (Osten)
		fill(xf, G, cz - 3, xf, G + 9, cz + 3, Blocks.AIR);
		fill(xf, G + 10, cz - 4, xf, G + 10, cz + 4, Blocks.CHISELED_POLISHED_BLACKSTONE);
		// Rosenfenster über dem Portal
		for (int dy = -3; dy <= 3; dy++) {
			for (int dz = -3; dz <= 3; dz++) {
				if (dy * dy + dz * dz <= 9) {
					set(xf, G + 16 + dy, cz + dz, Blocks.IRON_BARS);
				}
			}
		}
		// Seitenfenster
		for (int n = 6; n < 56; n += 5) {
			for (int dy = 6; dy <= 15; dy++) {
				set(xs(n), G + dy, z1, Blocks.IRON_BARS);
				set(xs(n), G + dy, z2, Blocks.IRON_BARS);
			}
		}
		// Säulenreihen und Bänke
		for (int n = 8; n <= 52; n += 6) {
			int x = xs(n);
			for (int z : new int[]{cz - 5, cz + 5}) {
				fill(x, G, z, x, G + wallH - 1, z, Blocks.POLISHED_BLACKSTONE_BRICKS);
				set(x, G, z, Blocks.CHISELED_POLISHED_BLACKSTONE);
				lantern(x, G + 8, z + (z < cz ? 1 : -1));
			}
		}
		for (int n = 6; n <= 40; n += 3) {
			for (int z : new int[]{cz - 3, cz - 2, cz + 2, cz + 3}) {
				set(xs(n), G, z, Blocks.COBBLED_DEEPSLATE_SLAB);
			}
		}
		for (int n = 4; n <= 44; n++) {
			for (int z = cz - 1; z <= cz + 1; z++) {
				set(xs(n), G - 1, z, Blocks.AMETHYST_BLOCK);
			}
		}
		// Altar im Westen
		for (int k = 0; k < 3; k++) {
			fill(xs(50 + k), G + k, cz - 6 + k, xs(50 + k), G + k, cz + 6 - k, Blocks.DEEPSLATE_TILES);
		}
		fill(xs(55), G + 3, cz, xs(55), G + 5, cz, Blocks.CRYING_OBSIDIAN);
		set(xs(54), G + 3, cz, Blocks.SOUL_CAMPFIRE);
		lantern(xs(55), G + 6, cz);

		// Zwei Türme an der Portalseite, Chorturm hinten
		for (int sz : new int[]{-10, 10}) {
			tower(xf, cz + sz, 4, G, 34, true);
		}
		tower(xb, cz, 5, G, 28, true);

		// Strebepfeiler
		for (int n = 8; n <= 52; n += 6) {
			int x = xs(n);
			for (int z : new int[]{z1 - 3, z2 + 3}) {
				fill(x, G, z, x, G + 14, z, Blocks.POLISHED_BLACKSTONE_BRICKS);
				int dir = z < cz ? 1 : -1;
				for (int k = 0; k < 3; k++) {
					set(x, G + 14 - k, z + dir * (k + 1), Blocks.POLISHED_BLACKSTONE_BRICKS);
				}
				set(x, G + 15, z, Blocks.CRYING_OBSIDIAN);
			}
		}
		// Vorplatz Richtung Straße mit Laternen-Allee
		for (int x = xf + 4; x <= xf + 26; x += 4) {
			post(x, G, cz - 4, 4);
			post(x, G, cz + 4, 4);
			fill(x, G - 1, cz - 2, x, G - 1, cz + 2, Blocks.CHISELED_POLISHED_BLACKSTONE);
		}
		fill(xf + 1, G - 1, cz - 2, xf + 26, G - 1, cz + 2, Blocks.POLISHED_BLACKSTONE_BRICKS);
	}

	// ================================================================== Große Arena (gegenüber der Kathedrale)

	private static void arena() {
		int cx = ARENA_X;
		int cz = ARENA_Z;
		clearArea(cx - 40, cz - 40, cx + 39, cz + 39);
		for (int x = -33; x <= 33; x++) {
			for (int z = -33; z <= 33; z++) {
				double d = Math.sqrt(x * x + z * z);
				boolean aisle = Math.abs(x) <= 2 || Math.abs(z) <= 2;
				if (d >= 27.5 && d <= 30.0) {
					if (aisle && d < 29.0) {
						set(cx + x, G + 9, cz + z, Blocks.CHISELED_POLISHED_BLACKSTONE);
						for (int y = G + 10; y < G + 16; y++) {
							set(cx + x, y, cz + z, wall());
						}
					} else if (aisle) {
						for (int y = G; y < G + 9; y++) {
							set(cx + x, y, cz + z, Blocks.AIR);
						}
						set(cx + x, G + 9, cz + z, Blocks.CHISELED_POLISHED_BLACKSTONE);
					} else {
						for (int y = G; y < G + 16; y++) {
							set(cx + x, y, cz + z, wall());
						}
					}
					if (!aisle && (x + z) % 2 == 0) {
						set(cx + x, G + 16, cz + z, Blocks.POLISHED_BLACKSTONE_BRICKS);
					}
				} else if (d >= 13.0 && d < 27.5) {
					int height = 1 + (int) ((d - 13.0) / 2.5);
					boolean ring = ((int) ((d - 13.0) / 2.5)) % 2 == 0;
					fill(cx + x, G, cz + z, cx + x, G + height - 1, cz + z, ring ? Blocks.DEEPSLATE_TILES : Blocks.COBBLED_DEEPSLATE);
					if (aisle) {
						fill(cx + x, G, cz + z, cx + x, G + height - 1, cz + z, Blocks.POLISHED_BLACKSTONE_BRICKS);
					}
				} else if (d < 13.0) {
					set(cx + x, G - 1, cz + z, d < 10.0 ? Blocks.SOUL_SAND : Blocks.CHISELED_POLISHED_BLACKSTONE);
				}
			}
		}
		// Podium in der Mitte
		fill(cx - 3, G, cz - 3, cx + 3, G + 1, cz + 3, Blocks.DEEPSLATE_TILES);
		set(cx, G + 2, cz, Blocks.CRYING_OBSIDIAN);
		set(cx, G + 3, cz, Blocks.SOUL_CAMPFIRE);
		// Fackeln auf den Rängen
		for (int n = 0; n < 16; n++) {
			double a = n * Math.PI * 2.0 / 16.0;
			post(cx + (int) Math.round(Math.cos(a) * 25.5), G + 6, cz + (int) Math.round(Math.sin(a) * 25.5), 3);
		}
		// Eck-Türme und Straßenlaternen
		for (int sx : new int[]{-36, 36}) {
			for (int sz : new int[]{-36, 36}) {
				tower(cx + sx, cz + sz, 3, G, 20, true);
			}
		}
		for (int x = cx - 38; x <= cx - 32; x += 3) {
			post(x, G, cz - 6, 4);
			post(x, G, cz + 6, 4);
		}
	}
}
