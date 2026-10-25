package com.example.shadowrealm;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;

/** Die Schattenklinge: verhält sich wie ein Netherite-Schwert, markiert aber Kills für die Schattenwelt. */
public class ShadowBladeItem extends Item {
	public ShadowBladeItem(Item.Properties properties) {
		super(properties);
	}

	public static boolean isHolding(Player player) {
		return player.getMainHandItem().getItem() instanceof ShadowBladeItem;
	}
}
