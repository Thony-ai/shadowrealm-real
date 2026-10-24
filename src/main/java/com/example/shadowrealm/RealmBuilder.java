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
 * Baut die Schattenwelt Schritt für Schritt (ein Bauabschnitt pro Tick) auf:
 * Raster aus 9 x 9 Feldern (je 40 x 40 Blöcke, Bereich -180 bis +180), jedes Feld mit einem Bauwerk
 * und Dekoration, dazu eine riesige Burg, eine Kathedrale, ein Platz am Spawn und beleuchtete Straßen.
 * Der Fortschritt wird gespeichert, ein Neustart setzt den Bau fort.
 */
final class RealmBuilder {
	/** Erster freier Block über dem Boden (Schichten: 0 Bedrock, 1-3 Tiefenschiefer, 4-5 Blackstone, 6 poliert). */
	private static final int G = 7;
	private static final int CELL = 40;
	private static final int RANGE = 4;
	private static final int TOP = 62;

	// Riesenburg: Felder x 2..3, z -1..0 => Bereich x 60..139, z -60..19
	private static final int CASTLE_X = 100;
	private static final int CASTLE_Z = -20;
	// Kathedrale: Felder x -3..-2, z 1..2 => Bereich x -140..-61, z 20..99
	private static final int CATHEDRAL_X = -100;
	private static final int CATHEDRAL_Z = 60;

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

	private static List<int[]> steps() {
		List<int[]> list = new ArrayList<>();
		list.add(new int[]{PLAZA, 0, 0});
		list.add(new int[]{CASTLE, 2, -1});
		list.add(new int[]{CATHEDRAL, -3, 1});
		for (int j = -RANGE; j <= RANGE; j++) {
			for (int i = -RANGE; i <= RANGE; i++) {
				boolean castleCell = (i == 2 || i == 3) && (j == -1 || j == 0);
				boolean cathedralCell = (i == -3 || i == -2) && (j == 1 || j == 2);
				boolean plaza = i == 0 && j == 0;
				if (!castleCell && !cathedralCell && !plaza) {
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
		if (state == null || state.data.realmBuildDone) {
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
				case CASTLE -> castle();
				case CATHEDRAL -> cathedral();
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
		int type = Math.floorMod(i * 3 + j * 7 + 40, 10);
		switch (type) {
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
		boolean castle = x >= CASTLE_X - 40 && x <= CASTLE_X + 39 && z >= CASTLE_Z - 40 && z <= CASTLE_Z + 39;
		boolean cathedral = x >= CATHEDRAL_X - 40 && x <= CATHEDRAL_X + 39 && z >= CATHEDRAL_Z - 40 && z <= CATHEDRAL_Z + 39;
		return castle || cathedral;
	}

	/** Straßen auf den Linien zwischen den Feldern, mit Laternen. */
	private static void roads(boolean alongX) {
		int min = -(RANGE * CELL + 20);
		int max = RANGE * CELL + 19;
		for (int k = -RANGE - 1; k <= RANGE; k++) {
			int line = k * CELL + 20;
			for (int t = min; t <= max; t++) {
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

	// ================================================================== Riesenburg

	private static void castle() {
		int cx = CASTLE_X;
		int cz = CASTLE_Z;
		int half = 36;
		clearArea(cx - 40, cz - 40, cx + 39, cz + 39);
		scatter(cx - 38, cz - 38, cx + 37, cz + 37, 90);

		// Hofboden
		for (int x = cx - half + 2; x <= cx + half - 2; x++) {
			for (int z = cz - half + 2; z <= cz + half - 2; z++) {
				set(x, G - 1, z, ((x + z) & 3) == 0 ? Blocks.DEEPSLATE_TILES : Blocks.DEEPSLATE_BRICKS);
			}
		}

		// Ringmauer: 2 Blöcke dick, 16 hoch, mit Zinnen
		for (int x = cx - half; x <= cx + half; x++) {
			for (int z = cz - half; z <= cz + half; z++) {
				int edge = Math.max(Math.abs(x - cx), Math.abs(z - cz));
				if (edge >= half - 1) {
					for (int y = G; y < G + 16; y++) {
						set(x, y, z, wall());
					}
					if ((x + z) % 2 == 0 && edge == half) {
						set(x, G + 16, z, Blocks.POLISHED_BLACKSTONE_BRICKS);
					}
				}
			}
		}
		// Torhaus im Westen
		fill(cx - half, G, cz - 4, cx - half + 1, G + 11, cz + 4, Blocks.AIR);
		fill(cx - half, G + 12, cz - 5, cx - half + 1, G + 12, cz + 5, Blocks.CHISELED_POLISHED_BLACKSTONE);
		for (int sz : new int[]{-9, 9}) {
			tower(cx - half, cz + sz, 4, G, 28, true);
		}
		// Ecktürme und Seitentürme
		int[][] spots = {{half, half}, {half, -half}, {-half, half}, {-half, -half}, {0, half}, {0, -half}, {half, 0}};
		for (int[] s : spots) {
			tower(cx + s[0], cz + s[1], 4, G, 30, true);
		}
		// Bergfried
		int kx = cx + 8;
		keep(kx, cz);

		// Pfad vom Tor zum Bergfried
		for (int x = cx - half + 2; x <= kx - 14; x++) {
			for (int z = cz - 2; z <= cz + 2; z++) {
				set(x, G - 1, z, Blocks.CHISELED_POLISHED_BLACKSTONE);
			}
			if ((x - cx) % 6 == 0) {
				post(x, G, cz - 3, 3);
				post(x, G, cz + 3, 3);
			}
		}

		// Gebäude im Hof: Ställe, Schmiede, Wachhäuser, Brunnen
		int[][] houses = {{-22, -24}, {-22, 24}, {22, -26}, {22, 26}, {-6, -26}, {-6, 26}, {40 - 36, -26}};
		for (int[] h : houses) {
			int hx = cx + h[0];
			int hz = cz + h[1];
			if (Math.abs(hz - cz) > half - 5 || Math.abs(hx - cx) > half - 7) {
				continue;
			}
			box(hx - 5, G, hz - 4, hx + 5, G + 6, hz + 4);
			fill(hx, G, hz + (hz > cz ? -4 : 4), hx, G + 2, hz + (hz > cz ? -4 : 4), Blocks.AIR);
			set(hx - 2, G + 3, hz - 4, Blocks.IRON_BARS);
			set(hx + 2, G + 3, hz - 4, Blocks.IRON_BARS);
			roof(hx, G + 7, hz, 5);
			lantern(hx, G + 1, hz);
		}
		// Brunnen
		shell(cx - 22 - 2, G, cz - 2, cx - 22 + 2, G + 1, cz + 2);
		fill(cx - 22 - 1, G, cz - 1, cx - 22 + 1, G, cz + 1, Blocks.SOUL_SAND);
		set(cx - 22, G + 1, cz, Blocks.SOUL_CAMPFIRE);
	}

	private static void keep(int kx, int cz) {
		int h = 14;
		int height = 32;
		box(kx - h, G, cz - h, kx + h, G + height, cz + h);
		battlements(kx - h, cz - h, kx + h, cz + h, G + height);
		// Eingang im Westen (breit) mit Treppe
		fill(kx - h, G, cz - 3, kx - h, G + 7, cz + 3, Blocks.AIR);
		for (int k = 0; k < 4; k++) {
			fill(kx - h - 1 - k, G + 3 - k, cz - 3, kx - h - 1 - k, G + 3 - k, cz + 3, Blocks.POLISHED_BLACKSTONE_BRICKS);
		}
		// Böden mit Aufgangsloch
		for (int lvl = 10; lvl < height; lvl += 10) {
			fill(kx - h + 1, G + lvl, cz - h + 1, kx + h - 1, G + lvl, cz + h - 1, Blocks.POLISHED_BLACKSTONE_BRICKS);
			fill(kx - h + 1, G + lvl, cz - h + 1, kx - h + 3, G + lvl, cz - h + 3, Blocks.AIR);
			for (int k = 0; k < 10; k++) {
				fill(kx - h + 1 + (k % 3), G + lvl - 10 + k, cz - h + 1, kx - h + 1 + (k % 3), G + lvl - 10 + k, cz - h + 1, Blocks.POLISHED_BLACKSTONE_BRICKS);
			}
		}
		// Säulen der großen Halle
		for (int x = kx - 10; x <= kx + 10; x += 5) {
			for (int z : new int[]{cz - 7, cz + 7}) {
				fill(x, G, z, x, G + 9, z, Blocks.POLISHED_BLACKSTONE_BRICKS);
				set(x, G, z, Blocks.CHISELED_POLISHED_BLACKSTONE);
				lantern(x, G + 8, z + (z < cz ? 1 : -1));
			}
		}
		// Thron
		for (int k = 0; k < 3; k++) {
			fill(kx + 8 + k, G + k, cz - 3, kx + 8 + k, G + k, cz + 3, Blocks.DEEPSLATE_TILES);
		}
		fill(kx + 12, G + 3, cz, kx + 12, G + 6, cz, Blocks.CRYING_OBSIDIAN);
		fill(kx + 12, G + 3, cz - 1, kx + 12, G + 4, cz - 1, Blocks.CRYING_OBSIDIAN);
		fill(kx + 12, G + 3, cz + 1, kx + 12, G + 4, cz + 1, Blocks.CRYING_OBSIDIAN);
		// Fenster
		for (int lvl = 4; lvl < height; lvl += 10) {
			for (int x = kx - 10; x <= kx + 10; x += 4) {
				set(x, G + lvl, cz - h, Blocks.IRON_BARS);
				set(x, G + lvl + 1, cz - h, Blocks.IRON_BARS);
				set(x, G + lvl, cz + h, Blocks.IRON_BARS);
				set(x, G + lvl + 1, cz + h, Blocks.IRON_BARS);
			}
		}
		// Eck-Türmchen
		for (int sx : new int[]{-h, h}) {
			for (int sz : new int[]{-h, h}) {
				tower(kx + sx, cz + sz, 3, G, 40, true);
			}
		}
		// Hauptturm
		box(kx - 5, G + height, cz - 5, kx + 5, G + 46, cz + 5);
		fill(kx - 4, G + height, cz - 4, kx + 4, G + height, cz + 4, Blocks.AIR);
		roof(kx, G + 47, cz, 5);
	}

	// ================================================================== Kathedrale

	private static void cathedral() {
		int cx = CATHEDRAL_X;
		int cz = CATHEDRAL_Z;
		clearArea(cx - 40, cz - 40, cx + 39, cz + 39);
		scatter(cx - 38, cz - 38, cx + 37, cz + 37, 70);

		int x1 = cx - 30;
		int x2 = cx + 30;
		int z1 = cz - 10;
		int z2 = cz + 10;
		int wallH = 22;

		// Hauptschiff
		shell(x1, G, z1, x2, G + wallH, z2);
		fill(x1 + 1, G - 1, z1 + 1, x2 - 1, G - 1, z2 - 1, Blocks.POLISHED_BLACKSTONE_BRICKS);
		// Satteldach
		for (int k = 0; k <= 10; k++) {
			for (int x = x1; x <= x2; x++) {
				set(x, G + wallH + 1 + k, z1 + k, Blocks.BLACKSTONE);
				set(x, G + wallH + 1 + k, z2 - k, Blocks.BLACKSTONE);
			}
			fill(x1, G + wallH + 1 + k, z1 + k, x1, G + wallH + 1 + k, z2 - k, wall());
			fill(x2, G + wallH + 1 + k, z1 + k, x2, G + wallH + 1 + k, z2 - k, wall());
		}
		// Portal im Westen
		fill(x1, G, cz - 3, x1, G + 9, cz + 3, Blocks.AIR);
		fill(x1, G + 10, cz - 4, x1, G + 10, cz + 4, Blocks.CHISELED_POLISHED_BLACKSTONE);
		// Rosenfenster
		for (int dy = -3; dy <= 3; dy++) {
			for (int dz = -3; dz <= 3; dz++) {
				if (dy * dy + dz * dz <= 9) {
					set(x1, G + 16 + dy, cz + dz, Blocks.IRON_BARS);
				}
			}
		}
		// Seitenfenster
		for (int x = x1 + 4; x < x2 - 2; x += 5) {
			for (int dy = 6; dy <= 15; dy++) {
				set(x, G + dy, z1, Blocks.IRON_BARS);
				set(x, G + dy, z2, Blocks.IRON_BARS);
			}
		}
		// Säulenreihen
		for (int x = x1 + 6; x <= x2 - 6; x += 6) {
			for (int z : new int[]{cz - 5, cz + 5}) {
				fill(x, G, z, x, G + wallH - 1, z, Blocks.POLISHED_BLACKSTONE_BRICKS);
				set(x, G, z, Blocks.CHISELED_POLISHED_BLACKSTONE);
				lantern(x, G + 8, z + (z < cz ? 1 : -1));
			}
		}
		// Bänke
		for (int x = x1 + 5; x <= x2 - 14; x += 3) {
			for (int z : new int[]{cz - 3, cz - 2, cz + 2, cz + 3}) {
				set(x, G, z, Blocks.COBBLED_DEEPSLATE_SLAB);
			}
		}
		// Altar
		for (int k = 0; k < 3; k++) {
			fill(x2 - 10 + k, G + k, cz - 6 + k, x2 - 10 + k, G + k, cz + 6 - k, Blocks.DEEPSLATE_TILES);
		}
		fill(x2 - 5, G + 3, cz, x2 - 5, G + 5, cz, Blocks.CRYING_OBSIDIAN);
		set(x2 - 6, G + 3, cz, Blocks.SOUL_CAMPFIRE);
		lantern(x2 - 5, G + 6, cz);

		// Zwei Türme an der Westfront
		for (int sz : new int[]{-10, 10}) {
			tower(x1, cz + sz, 4, G, 34, true);
		}
		// Chorturm im Osten
		tower(x2, cz, 5, G, 28, true);

		// Strebepfeiler
		for (int x = x1 + 6; x <= x2 - 6; x += 6) {
			for (int z : new int[]{z1 - 3, z2 + 3}) {
				fill(x, G, z, x, G + 14, z, Blocks.POLISHED_BLACKSTONE_BRICKS);
				int dir = z < cz ? 1 : -1;
				for (int k = 0; k < 3; k++) {
					set(x, G + 14 - k, z + dir * (k + 1), Blocks.POLISHED_BLACKSTONE_BRICKS);
				}
				set(x, G + 15, z, Blocks.CRYING_OBSIDIAN);
			}
		}
		// Vorplatz mit Laternen-Allee
		for (int x = x1 - 20; x < x1; x += 4) {
			post(x, G, cz - 4, 4);
			post(x, G, cz + 4, 4);
			fill(x, G - 1, cz - 2, x, G - 1, cz + 2, Blocks.CHISELED_POLISHED_BLACKSTONE);
		}
	}
}
