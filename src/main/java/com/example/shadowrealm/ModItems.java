package com.example.shadowrealm;

import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
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
		CreativeModeTabEvents.modifyOutputEvent(CreativeModeTabs.COMBAT)
				.register(output -> output.accept(SHADOW_BLADE));
	}
}
