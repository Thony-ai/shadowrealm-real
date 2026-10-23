package com.example.shadowrealm;

import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.ToolMaterial;

public final class ModItems {
	public static final ResourceKey<Item> SHADOW_BLADE_KEY =
			ResourceKey.create(Registries.ITEM, ShadowRealm.id("shadow_blade"));

	public static final Item SHADOW_BLADE = Registry.register(
			BuiltInRegistries.ITEM,
			SHADOW_BLADE_KEY,
			new ShadowBladeItem(new Item.Properties()
					.setId(SHADOW_BLADE_KEY)
					.sword(ToolMaterial.NETHERITE, 3.0F, -2.4F)
					.rarity(Rarity.EPIC)));

	private ModItems() {
	}

	public static void init() {
		ItemGroupEvents.modifyEntriesEvent(CreativeModeTabs.COMBAT)
				.register(entries -> entries.accept(SHADOW_BLADE));
	}
}
