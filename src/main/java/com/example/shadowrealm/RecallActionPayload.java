package com.example.shadowrealm;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Wird vom Client gesendet, wenn die Rückruf-Taste mit der Klinge in der Hand gedrückt wird. */
public record RecallActionPayload(int action) implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<RecallActionPayload> TYPE =
			new CustomPacketPayload.Type<>(ShadowRealm.id("recall_action"));

	public static final StreamCodec<ByteBuf, RecallActionPayload> CODEC =
			ByteBufCodecs.VAR_INT.map(RecallActionPayload::new, RecallActionPayload::action);

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
