package net.sprocketgames.atmosphere.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.sprocketgames.atmosphere.Atmosphere;

/**
 * Packet carrying the current Terraform Index (Ti) to clients so the HUD can display it.
 */
public record TerraformIndexSyncPayload(long terraformIndex) implements CustomPacketPayload {
    public static final Type<TerraformIndexSyncPayload> TYPE = new Type<>(Atmosphere.id("terraform_index_sync"));
    public static final StreamCodec<RegistryFriendlyByteBuf, TerraformIndexSyncPayload> STREAM_CODEC = StreamCodec.of(
            (buffer, payload) -> buffer.writeLong(payload.terraformIndex),
            buffer -> new TerraformIndexSyncPayload(buffer.readLong()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
