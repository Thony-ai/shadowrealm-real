package com.example.shadowrealm.client;

import net.minecraft.network.chat.Component;

/** Die eigene Seele, wie der Server sie meldet (nur zur Anzeige). */
public final class ClientSoul {
	public static int rank = 2;
	public static int karma = 0;
	public static boolean ghost = false;
	public static int stolen = 0;

	private static final int[] COLORS = {0xFFAA0000, 0xFFFF5555, 0xFFAAAAAA, 0xFF55FF55, 0xFFFFD700};

	private ClientSoul() {
	}

	public static void apply(String text) {
		try {
			String[] parts = text.split("\\|");
			rank = Integer.parseInt(parts[0]);
			karma = Integer.parseInt(parts[1]);
			ghost = "1".equals(parts[2]);
			stolen = Integer.parseInt(parts[3]);
		} catch (RuntimeException ignored) {
		}
	}

	public static int color() {
		return ghost ? 0xFF8888AA : COLORS[Math.max(0, Math.min(4, rank))];
	}

	/** Kurzform fürs Inventar. */
	public static String shortLabel() {
		if (ghost) {
			return Component.translatable("gui.shadowrealm.ghost").getString();
		}
		return Component.translatable("gui.shadowrealm.rank." + rank).getString();
	}

	public static String label() {
		if (ghost) {
			return Component.translatable("gui.shadowrealm.soul_none").getString();
		}
		return Component.translatable("gui.shadowrealm.soul_rank",
				Component.translatable("gui.shadowrealm.rank." + rank).getString()).getString();
	}
}
