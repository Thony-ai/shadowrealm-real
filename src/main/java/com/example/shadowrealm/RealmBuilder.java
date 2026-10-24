package com.example.shadowrealm;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Baut die Schattenwelt Schritt für Schritt (ein Bauabschnitt pro Tick) auf.
 * Alles ist entlang der Blickrichtung vom Spawn aus (nach Süden, +z) geordnet und spiegelsymmetrisch:
 *   - Spawn-Platz bei (0, 0)
 *   - Prachtstraße geradeaus (z 20..99) mit Statuen und Torbogen
 *   - dahinter die riesige Burg mit Thronsaal, Tor zum Spawn, genau mittig (x -56..56, z 104..212)
 *   - links der Straße die Kathedrale, rechts die große Arena
 *   - hinter dem Spawn Obeliken-, Pyramiden- und Turmreihen, außen Friedhöfe, Märkte und Festungen
 * Der Fortschritt wird gespeichert, ein Neustart setzt den Bau fort.
 */
final class RealmBuilder {
	/** Erster freier Block über dem Boden (Schichten: 0 Bedrock, 1-3 Tiefenschiefer, 4-5 Blackstone, 6 poliert). */
	private static final int G = 7;
	private static final int CELL = 40;
	private static final int TOP = 62;
	private static final int VERSION = 3;

	// Felder: x -4..4, z -4..5 (Mitte eines Feldes = Index * 40)
	private static final int I_MIN = -4;
	private static final int I_MAX = 4;
	private static final int J_MIN = -4;
	private static final int J_MAX = 5;

	// Burg: Felder x -1..1, z 3..5 => x -60..59, z 100..219
	private static final int CASTLE_Z1 = 104; // Außenmauer vorne (Tor zum Spawn)
	private static final int CASTLE_Z2 = 212; // Außenmauer hinten
	private static final int CASTLE_HALF = 56;
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

	private static boolean isCastleCell(int i, int j) {
		return i >= -1 && i <= 1 && j >= 3 && j <= 5;
	}

	private static boolean isCathedralCell(int i, int j) {
		return (i == -3 || i == -2) && (j == 1 || j == 2);
	}

	private static boolean isArenaCell(int i, int j) {
		return (i == 2 || i == 3) && (j == 1 || j == 2);
	}

	private static List<int[]> steps() {
		List<int[]> list = new ArrayList<>();
		list.add(new int[]{PLAZA, 0, 0});
		list.add(new int[]{AVENUE, 0, 0});
		for (int part = 0; part < 10; part++) {
			list.add(new int[]{CASTLE, part, 0});
		}
		list.add(new int[]{CATHEDRAL, 0, 0});
		list.add(new int[]{ARENA, 0, 0});
		for (int j = J_MIN; j <= J_MAX; j++) {
			for (int i = I_MIN; i <= I_MAX; i++) {
				boolean plaza = i == 0 && j == 0;
				boolean avenue = i == 0 && (j == 1 || j == 2);
				if (!isCastleCell(i, j) && !isCathedralCell(i, j) && !isArenaCell(i, j) && !plaza && !avenue) {
					list.add(new int[]{PLOT, i, j});
				}
			}
		}
		list.add(new int[]{ROADS_X, 0, 0});
		list.add(new int[]{ROADS_Z, 0, 0});
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
				case PLOT -> plot(step[1], step[2]);
				case ROADS_X -> roads(true);
				case ROADS_Z -> roads(false);
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

	// ================================================================== Hilfsfunktionen

	private static void set(int x, int y, int z, Block block) {
		if (y < 0 || y > 63) {
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

	// ================================================================== Felder

	private static void plot(int i, int j) {
		int cx = i * CELL;
		int cz = j * CELL;
		clearArea(cx - 20, cz - 20, cx + 19, cz + 19);
		scatter(cx - 18, cz - 18, cx + 17, cz + 17, 70);
		switch (typeFor(i, j)) {
			case 0 -> bigTower(cx, cz);
			case 1 -> hall(cx, cz);
			case 2 -> outpost(cx, cz);
			case 3 -> graveyard(cx, cz);
			case 4 -> pillarCourt(cx, cz);
			case 5 -> pyramid(cx, cz);
			case 6 -> spire(cx, cz);
			case 7 -> market(cx, cz);
			case 8 -> coliseum(cx, cz);
			default -> bridgeTowers(cx, cz);
		}
	}

	/** Ordnung nach Zonen, links und rechts der Mittelachse gleich (spiegelsymmetrisch). */
	private static int typeFor(int i, int j) {
		int a = Math.abs(i);
		if (j < 0) {
			// hinter dem Spawn: Reihen
			return switch (j) {
				case -1 -> 4; // Säulenhöfe
				case -2 -> 5; // Pyramiden
				case -3 -> 6; // Spitzen
				default -> 0; // Türme
			};
		}
		if (j == 0) {
			return a == 1 ? 2 : (a == 2 ? 9 : 7);
		}
		if (j <= 2) {
			if (a == 1) {
				return j == 1 ? 0 : 1;
			}
			return a == 4 ? 3 : 2;
		}
		// links und rechts der Burg
		return switch (a) {
			case 2 -> j == 5 ? 0 : 1;
			case 3 -> j == 3 ? 2 : (j == 4 ? 9 : 6);
			default -> j == 3 ? 5 : (j == 4 ? 3 : 4);
		};
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
	// ================================================================== Straßen

	private static boolean inMegaStructure(int x, int z) {
		boolean castle = x >= -60 && x <= 59 && z >= 100 && z <= 219;
		boolean cathedral = x >= -140 && x <= -61 && z >= 20 && z <= 99;
		boolean arena = x >= 60 && x <= 139 && z >= 20 && z <= 99;
		boolean avenue = x >= -20 && x <= 19 && z >= 20 && z <= 99;
		return castle || cathedral || arena || avenue;
	}

	/** Straßen auf den Linien zwischen den Feldern, mit Laternen. */
	private static void roads(boolean alongX) {
		int xMin = I_MIN * CELL - 20;
		int xMax = I_MAX * CELL + 19;
		int zMin = J_MIN * CELL - 20;
		int zMax = J_MAX * CELL + 19;
		int kMin = alongX ? J_MIN - 1 : I_MIN - 1;
		int kMax = alongX ? J_MAX : I_MAX;
		for (int k = kMin; k <= kMax; k++) {
			int line = k * CELL + 20;
			int tMin = alongX ? xMin : zMin;
			int tMax = alongX ? xMax : zMax;
			for (int t = tMin; t <= tMax; t++) {
				int x = alongX ? t : line;
				int z = alongX ? line : t;
				if (inMegaStructure(x, z)) {
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
		clearArea(-20, 20, 19, 99);
		for (int z = 20; z <= 99; z++) {
			for (int x = -6; x <= 6; x++) {
				boolean band = z % 8 == 0 || Math.abs(x) == 6;
				set(x, G - 1, z, band ? Blocks.CHISELED_POLISHED_BLACKSTONE : Blocks.POLISHED_BLACKSTONE_BRICKS);
			}
			if (z % 4 == 0) {
				set(-7, G - 1, z, Blocks.CRYING_OBSIDIAN);
				set(7, G - 1, z, Blocks.CRYING_OBSIDIAN);
			}
		}
		// Obelisken-Paare und Seelenfeuer entlang der Straße
		for (int z = 26; z <= 92; z += 12) {
			int h = 5 + ((z / 12) % 3) * 2;
			for (int s : new int[]{-1, 1}) {
				int x = s * 9;
				fill(x, G, z, x, G + h, z, Blocks.POLISHED_BLACKSTONE_BRICKS);
				set(x, G, z, Blocks.CHISELED_POLISHED_BLACKSTONE);
				set(x, G + h + 1, z, Blocks.CRYING_OBSIDIAN);
				set(s * 7, G, z + 6, Blocks.SOUL_CAMPFIRE);
				post(s * 5, G, z + 3, 3);
			}
		}
		// Ritter-Statuen
		for (int z : new int[]{38, 82}) {
			for (int s : new int[]{-1, 1}) {
				int x = s * 13;
				fill(x - 2, G, z - 2, x + 2, G + 1, z + 2, Blocks.DEEPSLATE_BRICKS);
				fill(x, G + 2, z, x, G + 8, z, Blocks.POLISHED_BLACKSTONE_BRICKS);
				fill(x - 1, G + 7, z, x + 1, G + 7, z, Blocks.BLACKSTONE_WALL);
				set(x, G + 9, z, Blocks.CHISELED_POLISHED_BLACKSTONE);
				set(x, G + 10, z, Blocks.CRYING_OBSIDIAN);
				fill(x + s, G + 2, z, x + s, G + 6, z, Blocks.IRON_BARS);
			}
		}
		// Torbogen in der Mitte der Straße
		for (int s : new int[]{-1, 1}) {
			int x = s * 9;
			fill(x - 1, G, 60, x + 1, G + 15, 61, Blocks.POLISHED_BLACKSTONE_BRICKS);
			lantern(x, G + 16, 60);
		}
		fill(-10, G + 14, 60, 10, G + 16, 61, Blocks.CHISELED_POLISHED_BLACKSTONE);
		fill(-8, G + 14, 60, 8, G + 14, 61, Blocks.POLISHED_BLACKSTONE_BRICKS);
		fill(-1, G + 17, 60, 1, G + 19, 61, Blocks.POLISHED_BLACKSTONE_BRICKS);
		set(0, G + 20, 60, Blocks.CRYING_OBSIDIAN);
		for (int x = -6; x <= 6; x += 6) {
			lantern(x, G + 13, 60);
		}
		// Vor der Burg: zwei hohe Wachtürme
		tower(-12, 94, 3, G, 22, true);
		tower(12, 94, 3, G, 22, true);
		scatter(-19, 20, -11, 99, 20);
		scatter(11, 20, 18, 99, 20);
	}

	// ================================================================== Riesenburg

	private static void castlePart(int part) {
		switch (part) {
			case 0 -> clearArea(-60, 100, -1, 159);
			case 1 -> clearArea(0, 100, 59, 159);
			case 2 -> clearArea(-60, 160, -1, 219);
			case 3 -> clearArea(0, 160, 59, 219);
			case 4 -> castleWalls();
			case 5 -> castleTowers();
			case 6 -> outerWard();
			case 7 -> keepShell();
			case 8 -> throneRoom();
			default -> keepTowersAndSpire();
		}
	}

	private static void castleWalls() {
		int hw = CASTLE_HALF;
		int za = CASTLE_Z1;
		int zb = CASTLE_Z2;
		// Hofboden mit Muster
		for (int x = -hw + 3; x <= hw - 3; x++) {
			for (int z = za + 3; z <= zb - 3; z++) {
				set(x, G - 1, z, (((x >> 2) + (z >> 2)) & 1) == 0 ? Blocks.DEEPSLATE_TILES : Blocks.DEEPSLATE_BRICKS);
			}
		}
		// Ringmauer, 3 dick, 20 hoch, Zinnen
		for (int x = -hw; x <= hw; x++) {
			for (int z = za; z <= zb; z++) {
				int edge = Math.min(Math.min(x + hw, hw - x), Math.min(z - za, zb - z));
				if (edge < 3) {
					for (int y = G; y < G + 20; y++) {
						set(x, y, z, wall());
					}
					if (edge == 0 && (x + z) % 2 == 0) {
						set(x, G + 20, z, Blocks.POLISHED_BLACKSTONE_BRICKS);
					}
				}
			}
		}
		// Haupttor zum Spawn (Mitte der Vorderseite)
		fill(-5, G, za, 5, G + 13, za + 2, Blocks.AIR);
		fill(-6, G + 14, za, 6, G + 15, za + 2, Blocks.CHISELED_POLISHED_BLACKSTONE);
		fill(-5, G + 10, za + 1, 5, G + 13, za + 1, Blocks.IRON_BARS);
		for (int x = -4; x <= 4; x += 2) {
			lantern(x, G + 9, za + 1);
		}
		// Innere Mauer (teilt Vorburg und Kernburg), 3 dick, 18 hoch, Tor in der Mitte
		for (int x = -hw + 3; x <= hw - 3; x++) {
			for (int z = 149; z <= 151; z++) {
				for (int y = G; y < G + 18; y++) {
					set(x, y, z, wall());
				}
				if (z == 151 && (x % 2 == 0)) {
					set(x, G + 18, z, Blocks.POLISHED_BLACKSTONE_BRICKS);
				}
			}
		}
		fill(-4, G, 149, 4, G + 11, 151, Blocks.AIR);
		fill(-5, G + 12, 149, 5, G + 13, 151, Blocks.CHISELED_POLISHED_BLACKSTONE);
		fill(-4, G + 9, 150, 4, G + 11, 150, Blocks.IRON_BARS);
	}

	private static void castleTowers() {
		int hw = CASTLE_HALF;
		int za = CASTLE_Z1;
		int zb = CASTLE_Z2;
		for (int s : new int[]{-1, 1}) {
			tower(s * hw, za, 6, G, 40, true);          // Ecktürme
			tower(s * hw, zb, 6, G, 40, true);
			tower(s * 13, za, 5, G, 38, true);          // Torhaus
			tower(s * 34, za, 4, G, 30, true);          // Vorderseite
			tower(s * hw, 130, 4, G, 30, true);         // Seiten
			tower(s * hw, 158, 4, G, 30, true);
			tower(s * hw, 186, 4, G, 30, true);
			tower(s * 28, zb, 5, G, 34, true);          // Rückseite
			tower(s * 40, 150, 4, G, 30, true);         // innere Mauer
		}
		tower(0, zb, 6, G, 38, true);
	}

	private static void outerWard() {
		// Prachtweg vom Tor bis zum Kerntor
		for (int z = CASTLE_Z1 + 3; z <= 148; z++) {
			for (int x = -4; x <= 4; x++) {
				boolean band = z % 6 == 0 || Math.abs(x) == 4;
				set(x, G - 1, z, band ? Blocks.CHISELED_POLISHED_BLACKSTONE : Blocks.POLISHED_BLACKSTONE_BRICKS);
			}
			if (z % 8 == 0) {
				post(-6, G, z, 4);
				post(6, G, z, 4);
			}
		}
		for (int s : new int[]{-1, 1}) {
			// Brunnen
			int fx = s * 16;
			shell(fx - 3, G, 125 - 3, fx + 3, G + 1, 125 + 3);
			fill(fx - 2, G, 125 - 2, fx + 2, G, 125 + 2, Blocks.WATER);
			fill(fx, G + 1, 125, fx, G + 4, 125, Blocks.POLISHED_BLACKSTONE_BRICKS);
			lantern(fx, G + 5, 125);
			// Kaserne
			int bx = s * 34;
			box(bx - 9, G, 114, bx + 9, G + 11, 128);
			fill(bx - 9, G + 12, 114, bx + 9, G + 12, 128, Blocks.POLISHED_BLACKSTONE_BRICKS);
			battlements(bx - 9, 114, bx + 9, 128, G + 13);
			int doorX = s > 0 ? bx - 9 : bx + 9;
			fill(doorX, G, 120, doorX, G + 3, 122, Blocks.AIR);
			for (int z = 117; z <= 125; z += 4) {
				set(bx, G + 4, z == 117 ? 114 : 128, Blocks.IRON_BARS);
			}
			fill(bx - 6, G, 116, bx + 6, G, 116, Blocks.DEEPSLATE_BRICKS);
			lantern(bx, G + 1, 121);
			// Schmiede
			int sx = s * 34;
			box(sx - 8, G, 132, sx + 8, G + 9, 144);
			fill(sx - 8, G + 10, 132, sx + 8, G + 10, 144, Blocks.POLISHED_BLACKSTONE_BRICKS);
			fill(sx + 5, G + 11, 140, sx + 5, G + 16, 140, Blocks.BLACKSTONE);
			set(sx + 5, G + 1, 140, Blocks.SOUL_CAMPFIRE);
			int forgeDoor = s > 0 ? sx - 8 : sx + 8;
			fill(forgeDoor, G, 137, forgeDoor, G + 3, 139, Blocks.AIR);
			// Mauer-Fackeln an der Außenmauer
			for (int z = 112; z <= 146; z += 8) {
				post(s * 52, G, z, 3);
			}
		}
	}

	private static void keepShell() {
		int zf = 162;
		int zb = 206;
		box(-34, G, zf, 34, G + 32, zb);
		battlements(-34, zf, 34, zb, G + 33);
		// Haupteingang mit Treppe
		fill(-5, G, zf, 5, G + 13, zf, Blocks.AIR);
		fill(-6, G + 14, zf, 6, G + 15, zf, Blocks.CHISELED_POLISHED_BLACKSTONE);
		fill(-9, G, zf - 4, 9, G, zf - 1, Blocks.POLISHED_BLACKSTONE_BRICKS);
		fill(-7, G, zf - 6, 7, G, zf - 5, Blocks.POLISHED_BLACKSTONE_BRICKS);
		for (int s : new int[]{-1, 1}) {
			post(s * 9, G + 1, zf - 4, 4);
			post(s * 6, G, zf - 8, 5);
		}
		// Fenster außen
		for (int z = 170; z <= 198; z += 8) {
			for (int s : new int[]{-1, 1}) {
				fill(s * 34, G + 4, z, s * 34, G + 10, z + 1, Blocks.IRON_BARS);
				fill(s * 34, G + 17, z, s * 34, G + 27, z + 1, Blocks.IRON_BARS);
			}
		}
		// Dach der Kernburg: Boden der Spitze
		fill(-33, G + 32, zf + 1, 33, G + 32, zb - 1, Blocks.POLISHED_BLACKSTONE_BRICKS);
	}

	private static void throneRoom() {
		int zf = 162;
		int zb = 206;
		// Boden mit Muster
		for (int x = -33; x <= 33; x++) {
			for (int z = zf + 1; z <= zb - 1; z++) {
				set(x, G - 1, z, (((x >> 2) + (z >> 2)) & 1) == 0 ? Blocks.POLISHED_BLACKSTONE_BRICKS : Blocks.DEEPSLATE_TILES);
			}
		}
		// Säulen mit Bögen
		for (int z = 170; z <= 202; z += 8) {
			for (int x : new int[]{-26, -12, 12, 26}) {
				fill(x, G, z, x, G + 30, z, Blocks.POLISHED_BLACKSTONE_BRICKS);
				set(x, G, z, Blocks.CHISELED_POLISHED_BLACKSTONE);
				fill(x - 1, G + 29, z - 1, x + 1, G + 30, z + 1, Blocks.CHISELED_POLISHED_BLACKSTONE);
			}
			for (int x : new int[]{-12, 12}) {
				lantern(x, G + 23, z + 4);
			}
		}
		for (int x : new int[]{-26, -12, 12, 26}) {
			fill(x, G + 24, 170, x, G + 25, 202, Blocks.POLISHED_BLACKSTONE_BRICKS);
		}
		// Galerien links und rechts
		for (int s : new int[]{-1, 1}) {
			fill(s * 33, G + 14, zf + 1, s * 28, G + 14, zb - 1, Blocks.POLISHED_BLACKSTONE_BRICKS);
			fill(s * 27, G + 15, zf + 6, s * 27, G + 15, zb - 1, Blocks.BLACKSTONE_WALL);
			for (int k = 0; k < 14; k++) {
				fill(s * 32, G + k, zf + 2 + k, s * 30, G + k, zf + 2 + k, Blocks.POLISHED_BLACKSTONE_BRICKS);
			}
			// Banner
			for (int z = 170; z <= 202; z += 8) {
				fill(s * 33, G + 6, z - 1, s * 33, G + 22, z + 1, Blocks.PURPLE_CONCRETE);
				fill(s * 33, G + 6, z, s * 33, G + 6, z, Blocks.CRYING_OBSIDIAN);
			}
		}
		// Teppich und Seelenfeuer
		for (int z = zf + 1; z <= 195; z++) {
			for (int x = -2; x <= 2; x++) {
				set(x, G, z, Blocks.PURPLE_CARPET);
			}
			set(-3, G, z, Blocks.BLACK_CARPET);
			set(3, G, z, Blocks.BLACK_CARPET);
		}
		for (int z = 168; z <= 192; z += 8) {
			set(-7, G, z, Blocks.SOUL_CAMPFIRE);
			set(7, G, z, Blocks.SOUL_CAMPFIRE);
		}
		// Podest in drei Stufen
		fill(-14, G, 196, 14, G, 197, Blocks.DEEPSLATE_TILES);
		fill(-12, G, 198, 12, G + 1, 199, Blocks.DEEPSLATE_TILES);
		fill(-10, G, 200, 10, G + 2, 205, Blocks.DEEPSLATE_TILES);
		fill(-10, G + 2, 200, 10, G + 2, 205, Blocks.POLISHED_BLACKSTONE_BRICKS);
		fill(-2, G + 2, 196, 2, G + 2, 199, Blocks.PURPLE_CARPET);
		// Thron
		set(0, G + 3, 202, Blocks.CHISELED_POLISHED_BLACKSTONE);
		fill(-2, G + 3, 203, 2, G + 3, 203, Blocks.DEEPSLATE_TILES);
		fill(-1, G + 3, 204, 1, G + 11, 204, Blocks.CRYING_OBSIDIAN);
		fill(-2, G + 3, 204, -2, G + 8, 204, Blocks.DEEPSLATE_TILES);
		fill(2, G + 3, 204, 2, G + 8, 204, Blocks.DEEPSLATE_TILES);
		set(-2, G + 3, 202, Blocks.DEEPSLATE_TILES);
		set(2, G + 3, 202, Blocks.DEEPSLATE_TILES);
		set(-2, G + 4, 202, Blocks.BLACKSTONE_WALL);
		set(2, G + 4, 202, Blocks.BLACKSTONE_WALL);
		set(-1, G + 12, 204, Blocks.CRYING_OBSIDIAN);
		set(0, G + 13, 204, Blocks.CRYING_OBSIDIAN);
		set(1, G + 12, 204, Blocks.CRYING_OBSIDIAN);
		fill(-3, G + 14, 205, 3, G + 20, 205, Blocks.CRYING_OBSIDIAN);
		for (int s : new int[]{-1, 1}) {
			set(s * 8, G + 3, 200, Blocks.SOUL_CAMPFIRE);
			set(s * 8, G + 3, 204, Blocks.SOUL_CAMPFIRE);
			fill(s * 6, G + 3, 205, s * 6, G + 22, 205, Blocks.POLISHED_BLACKSTONE_BRICKS);
		}
		for (int x = -9; x <= 9; x += 6) {
			fill(x, G + 8, zb, x, G + 26, zb, Blocks.IRON_BARS);
		}
	}

	private static void keepTowersAndSpire() {
		int zf = 162;
		int zb = 206;
		for (int s : new int[]{-1, 1}) {
			tower(s * 34, zf, 6, G, 44, true);
			tower(s * 34, zb, 6, G, 44, true);
			// Kapellen in den inneren Höfen
			box(s * 44 - 5, G, 180, s * 44 + 5, G + 14, 190);
			fill(s * 44 + (s > 0 ? -5 : 5), G, 184, s * 44 + (s > 0 ? -5 : 5), G + 3, 186, Blocks.AIR);
			roof(s * 44, G + 15, 185, 5);
			// Garten-Laternen
			for (int z = 154; z <= 200; z += 9) {
				post(s * 48, G, z, 3);
			}
		}
		// Große Spitze über dem Thronsaal
		box(-7, G + 32, 177, 7, G + 44, 191);
		fill(-6, G + 32, 178, 6, G + 32, 190, Blocks.AIR);
		for (int h = 36; h <= 42; h += 6) {
			for (int s : new int[]{-1, 1}) {
				set(s * 7, G + h, 184, Blocks.IRON_BARS);
				set(0, G + h, 177 + (s > 0 ? 14 : 0), Blocks.IRON_BARS);
			}
		}
		roof(0, G + 45, 184, 7);
		for (int s : new int[]{-1, 1}) {
			lantern(s * 34, G + 34, zf - 1);
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
				set(xs(n), G, z, Blocks.PURPLE_CARPET);
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
