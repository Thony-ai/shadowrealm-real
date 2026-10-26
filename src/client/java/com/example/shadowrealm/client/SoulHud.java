package com.example.shadowrealm.client;

import com.example.shadowrealm.ShadowRealm;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

/** Seelen-Anzeige links neben dem Offhand-Slot der Hotbar (nur Anzeige, kein echter Slot). */
public final class SoulHud {
	private static final Identifier TEXTURE = ShadowRealm.id("textures/item/soul.png");

	private SoulHud() {
	}

	public static void register() {
		HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT, ShadowRealm.id("soul_hud"), (graphics, tickCounter) -> {
			Minecraft mc = Minecraft.getInstance();
			if (mc.player == null) {
				return;
			}
			int w = mc.getWindow().getGuiScaledWidth();
			int h = mc.getWindow().getGuiScaledHeight();
			int x = w / 2 - 91 - 29 - 24;
			int y = h - 22;
			graphics.fill(x, y, x + 20, y + 20, 0x99000000);
			graphics.outline(x, y, 20, 20, ClientSoul.color());
			if (!ClientSoul.ghost) {
				graphics.blit(RenderPipelines.GUI_TEXTURED, TEXTURE, x + 2, y + 2, 0, 0, 16, 16, 16, 16);
			}
			String label = ClientSoul.label();
			graphics.text(mc.font, label, x - mc.font.width(label) - 3, y + 6, ClientSoul.color(), true);
		});
	}
}
