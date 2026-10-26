package com.example.shadowrealm;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Server -> Client: eigene Seele als Text "rang|karma|geist|gezogen|bösesoul". */
public record SoulPayload(String text) implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<SoulPayload> TYPE =
			new CustomPacketPayload.Type<>(ShadowRealm.id("soul"));

	public static final StreamCodec<ByteBuf, SoulPayload> CODEC =
			ByteBufCodecs.STRING_UTF8.map(SoulPayload::new, SoulPayload::text);

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
