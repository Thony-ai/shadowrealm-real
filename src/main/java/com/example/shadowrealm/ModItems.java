package com.example.shadowrealm;

import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
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

	public static final ResourceKey<Item> SOUL_KEY =
			ResourceKey.create(Registries.ITEM, ShadowRealm.id("soul"));

	/** Die Seele des Klingenbesitzers: der Preis dafür, jemanden in die Schattenwelt zu schicken. */
	public static final Item SOUL = Registry.register(
			BuiltInRegistries.ITEM,
			SOUL_KEY,
			new Item(new Item.Properties()
					.setId(SOUL_KEY)
					.rarity(Rarity.EPIC)));

	public static final ResourceKey<Item> ELIXIR_SWORD_KEY =
			ResourceKey.create(Registries.ITEM, ShadowRealm.id("elixir_sword"));

	public static final Item ELIXIR_SWORD = Registry.register(
			BuiltInRegistries.ITEM,
			ELIXIR_SWORD_KEY,
			new ElixirSwordItem(new Item.Properties()
					.setId(ELIXIR_SWORD_KEY)
					.sword(ToolMaterial.NETHERITE, 3.0F, -2.4F)
					.rarity(Rarity.EPIC)));

	private ModItems() {
	}

	public static ItemStack createSoul(String ownerName) {
		ItemStack stack = new ItemStack(SOUL);
		stack.set(DataComponents.CUSTOM_NAME, Component.literal("Seele von " + ownerName));
		return stack;
	}

	public static void init() {
		CreativeModeTabEvents.modifyOutputEvent(CreativeModeTabs.COMBAT)
				.register(output -> {
					output.accept(SHADOW_BLADE);
					output.accept(ELIXIR_SWORD);
				});
	}
}
