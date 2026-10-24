package com.example.shadowrealm;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.Random;

/**
 * Baut einmalig Bauwerke in die Schattenwelt: eine große Burg, Ruinen-Türme, einen Ring aus Säulen,
 * einen Torbogen und einen Friedhof. Der Bereich um den Spawn (0, 0) bleibt frei.
 */
final class RealmBuilder {
	private static ServerLevel level;
	private static Random random;

	private RealmBuilder() {
	}

	static void buildIfNeeded(MinecraftServer server, RealmState state) {
		if (state == null || state.data.realmBuilt) {
			return;
		}
		ServerLevel realm = server.getLevel(ShadowRealm.REALM);
		if (realm == null) {
			return;
		}
		try {
			level = realm;
			random = new Random(26_2_2026L);

			castle(56, 0);
			ruinedTower(-42, -30, 16);
			ruinedTower(34, 46, 12);
			ruinedTower(-48, 38, 18);
			ruinedTower(4, -58, 14);
			ruinedTower(-70, 0, 10);
			pillarRing();
			arch(0, 30);
			graveyard(-30, -14);

			state.data.realmBuilt = true;
			state.save();
		} catch (RuntimeException e) {
			System.err.println("[shadowrealm] Konnte die Schattenwelt nicht bebauen: " + e);
		} finally {
			level = null;
		}
	}

	// ================================================================== Hilfsfunktionen

	private static int groundY(int x, int z) {
		return level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
	}

	private static void set(int x, int y, int z, Block block) {
		level.setBlock(new BlockPos(x, y, z), block.defaultBlockState(), 2);
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

	/** Zufälliger dunkler Mauerstein (teils rissig), damit es nicht eintönig aussieht. */
	private static Block wallBlock() {
		int r = random.nextInt(10);
		if (r < 5) {
			return Blocks.POLISHED_BLACKSTONE_BRICKS;
		}
		if (r < 8) {
			return Blocks.CRACKED_POLISHED_BLACKSTONE_BRICKS;
		}
		return Blocks.BLACKSTONE;
	}

	/** Hohler Quader aus zufälligen Mauersteinen. y2 ist die obere Kante der Wand. */
	private static void hollow(int x1, int y1, int z1, int x2, int y2, int z2) {
		for (int x = x1; x <= x2; x++) {
			for (int y = y1; y <= y2; y++) {
				for (int z = z1; z <= z2; z++) {
					boolean edge = x == x1 || x == x2 || z == z1 || z == z2;
					if (edge) {
						set(x, y, z, wallBlock());
					} else {
						set(x, y, z, Blocks.AIR);
					}
				}
			}
		}
	}

	/** Zinnen: jeder zweite Block auf der Oberkante. */
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

	// ================================================================== Bauwerke

	private static void castle(int cx, int cz) {
		int y = groundY(cx, cz);
		int r = 14;

		// Boden des Hofes
		fill(cx - r, y - 1, cz - r, cx + r, y - 1, cz + r, Blocks.DEEPSLATE_BRICKS);

		// Ringmauer mit Zinnen
		hollow(cx - r, y, cz - r, cx + r, y + 8, cz + r);
		battlements(cx - r, cz - r, cx + r, cz + r, y + 9);

		// Tor zur Seite des Spawns (Westen)
		fill(cx - r, y, cz - 2, cx - r, y + 5, cz + 2, Blocks.AIR);
		fill(cx - r, y + 6, cz - 2, cx - r, y + 6, cz + 2, Blocks.CHISELED_POLISHED_BLACKSTONE);

		// Eck-Türme
		for (int sx : new int[]{-r, r}) {
			for (int sz : new int[]{-r, r}) {
				int tx = cx + sx;
				int tz = cz + sz;
				hollow(tx - 3, y, tz - 3, tx + 3, y + 17, tz + 3);
				battlements(tx - 3, tz - 3, tx + 3, tz + 3, y + 18);
				fill(tx - 2, y + 18, tz - 2, tx + 2, y + 18, tz + 2, Blocks.POLISHED_BLACKSTONE_BRICKS);
				set(tx, y + 19, tz, Blocks.SOUL_LANTERN);
				// Fenster aus Eisengittern
				set(tx, y + 10, tz - 3, Blocks.IRON_BARS);
				set(tx, y + 10, tz + 3, Blocks.IRON_BARS);
				set(tx - 3, y + 10, tz, Blocks.IRON_BARS);
				set(tx + 3, y + 10, tz, Blocks.IRON_BARS);
			}
		}

		// Bergfried in der Mitte
		hollow(cx - 5, y, cz - 5, cx + 5, y + 15, cz + 5);
		battlements(cx - 5, cz - 5, cx + 5, cz + 5, y + 16);
		fill(cx - 5, y + 8, cz - 5, cx + 5, y + 8, cz + 5, Blocks.POLISHED_BLACKSTONE_BRICKS); // Zwischendecke
		fill(cx - 5, y + 8, cz - 1, cx - 3, y + 8, cz + 1, Blocks.AIR); // Aufgang
		fill(cx - 5, y, cz - 1, cx - 5, y + 3, cz + 1, Blocks.AIR); // Tür nach Westen
		for (int i = 0; i < 4; i++) {
			set(cx - 3 + i, y + i, cz, Blocks.POLISHED_BLACKSTONE_BRICKS); // einfache Treppe
		}
		for (int h : new int[]{4, 11}) {
			set(cx, y + h, cz - 5, Blocks.IRON_BARS);
			set(cx, y + h, cz + 5, Blocks.IRON_BARS);
			set(cx + 5, y + h, cz, Blocks.IRON_BARS);
		}
		// Thron
		set(cx + 3, y, cz, Blocks.CRYING_OBSIDIAN);
		set(cx + 4, y, cz, Blocks.CRYING_OBSIDIAN);
		set(cx + 4, y + 1, cz, Blocks.CRYING_OBSIDIAN);
		set(cx + 4, y + 2, cz, Blocks.CRYING_OBSIDIAN);
		for (int dz : new int[]{-4, 4}) {
			set(cx + 4, y, cz + dz, Blocks.SOUL_LANTERN);
			set(cx - 4, y, cz + dz, Blocks.SOUL_LANTERN);
		}

		// Laternen im Hof
		for (int dx = -10; dx <= 10; dx += 5) {
			set(cx + dx, y, cz - 10, Blocks.SOUL_LANTERN);
			set(cx + dx, y, cz + 10, Blocks.SOUL_LANTERN);
		}
	}

	private static void ruinedTower(int cx, int cz, int height) {
		int y = groundY(cx, cz);
		fill(cx - 4, y - 1, cz - 4, cx + 4, y - 1, cz + 4, Blocks.DEEPSLATE_BRICKS);
		hollow(cx - 3, y, cz - 3, cx + 3, y + height, cz + 3);
		// Tür nach Süden
		fill(cx, y, cz + 3, cx, y + 2, cz + 3, Blocks.AIR);
		// Ruine: oben fehlen Steine
		for (int x = cx - 3; x <= cx + 3; x++) {
			for (int z = cz - 3; z <= cz + 3; z++) {
				boolean edge = x == cx - 3 || x == cx + 3 || z == cz - 3 || z == cz + 3;
				if (!edge) {
					continue;
				}
				for (int h = 5; h <= height; h++) {
					if (random.nextInt(100) < 4 + h * 2) {
						set(x, y + h, z, Blocks.AIR);
					}
				}
			}
		}
		// Etage und Laterne
		fill(cx - 2, y + 5, cz - 2, cx + 2, y + 5, cz + 2, Blocks.POLISHED_BLACKSTONE_BRICKS);
		set(cx, y, cz, Blocks.SOUL_LANTERN);
	}

	private static void pillarRing() {
		for (int i = 0; i < 10; i++) {
			double angle = i * (Math.PI * 2.0 / 10.0);
			int x = (int) Math.round(Math.cos(angle) * 20.0);
			int z = (int) Math.round(Math.sin(angle) * 20.0);
			int y = groundY(x, z);
			int h = 5 + random.nextInt(6);
			fill(x, y, z, x, y + h, z, Blocks.POLISHED_BLACKSTONE_BRICKS);
			set(x, y + h + 1, z, Blocks.CRYING_OBSIDIAN);
			set(x, y, z, Blocks.CHISELED_POLISHED_BLACKSTONE);
		}
	}

	private static void arch(int cx, int cz) {
		int y = groundY(cx, cz);
		for (int dx : new int[]{-5, 5}) {
			fill(cx + dx, y, cz, cx + dx, y + 10, cz + 1, Blocks.POLISHED_BLACKSTONE_BRICKS);
			set(cx + dx, y + 11, cz, Blocks.SOUL_LANTERN);
		}
		fill(cx - 5, y + 10, cz, cx + 5, y + 11, cz + 1, Blocks.CHISELED_POLISHED_BLACKSTONE);
		fill(cx - 4, y + 10, cz, cx + 4, y + 10, cz + 1, Blocks.POLISHED_BLACKSTONE_BRICKS);
		set(cx, y + 9, cz, Blocks.CRYING_OBSIDIAN);
	}

	private static void graveyard(int cx, int cz) {
		for (int gx = 0; gx < 5; gx++) {
			for (int gz = 0; gz < 3; gz++) {
				int x = cx + gx * 3;
				int z = cz + gz * 3;
				int y = groundY(x, z);
				set(x, y, z, Blocks.BLACKSTONE_WALL);
				set(x, y + 1, z, Blocks.BLACKSTONE_WALL);
				set(x, y, z + 1, Blocks.COBBLED_DEEPSLATE);
			}
		}
		int y = groundY(cx + 6, cz - 3);
		set(cx + 6, y, cz - 3, Blocks.SOUL_LANTERN);
	}
}
