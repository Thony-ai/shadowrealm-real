package com.example.shadowrealm;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Server -> Client: Inhalt des Menüs als JSON-Text. */
public record MenuPayload(String json) implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<MenuPayload> TYPE =
			new CustomPacketPayload.Type<>(ShadowRealm.id("menu"));

	public static final StreamCodec<ByteBuf, MenuPayload> CODEC =
			ByteBufCodecs.STRING_UTF8.map(MenuPayload::new, MenuPayload::json);

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
