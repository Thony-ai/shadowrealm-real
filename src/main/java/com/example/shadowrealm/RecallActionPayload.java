package com.example.shadowrealm;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Client -> Server: Aktion des Spielers.
 * Mögliche Werte: "open", "recall:&lt;key&gt;", "recallall", "enter", "leave",
 * "army_arm", "army_key", "army_dismiss".
 */
public record RecallActionPayload(String action) implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<RecallActionPayload> TYPE =
			new CustomPacketPayload.Type<>(ShadowRealm.id("recall_action"));

	public static final StreamCodec<ByteBuf, RecallActionPayload> CODEC =
			ByteBufCodecs.STRING_UTF8.map(RecallActionPayload::new, RecallActionPayload::action);

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
