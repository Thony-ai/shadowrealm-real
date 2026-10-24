package com.example.shadowrealm;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

public class ShadowRealm implements ModInitializer {
	public static final String MOD_ID = "shadowrealm";

	/** Die Dimension wird per Datapack definiert: data/shadowrealm/dimension/shadow_realm.json */
	public static final ResourceKey<Level> REALM =
			ResourceKey.create(Registries.DIMENSION, id("shadow_realm"));

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}

	@Override
	public void onInitialize() {
		ModItems.init();

		// Client -> Server: Aktionen aus Menü und Tastenkombination
		PayloadTypeRegistry.serverboundPlay().register(RecallActionPayload.TYPE, RecallActionPayload.CODEC);
		// Server -> Client: Inhalt des Menüs
		PayloadTypeRegistry.clientboundPlay().register(MenuPayload.TYPE, MenuPayload.CODEC);
		// Server -> Client: eigene Seele (Rang) für die Anzeige
		PayloadTypeRegistry.clientboundPlay().register(SoulPayload.TYPE, SoulPayload.CODEC);

		ServerPlayNetworking.registerGlobalReceiver(RecallActionPayload.TYPE,
				(payload, context) -> RealmManager.handleAction(context.player(), payload.action()));

		RealmManager.register();
	}
}
