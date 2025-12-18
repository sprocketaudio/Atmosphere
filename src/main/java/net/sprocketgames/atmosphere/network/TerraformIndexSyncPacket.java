package net.sprocketgames.atmosphere.network;

import java.util.function.Supplier;

import net.minecraft.network.FriendlyByteBuf;
import net.neoforged.neoforge.network.NetworkEvent;
import net.sprocketgames.atmosphere.client.ClientTerraformIndex;

/**
 * Packet carrying the current Terraform Index (Ti) to clients so the HUD can display it.
 */
public class TerraformIndexSyncPacket {
    private final long terraformIndex;

    public TerraformIndexSyncPacket(long terraformIndex) {
        this.terraformIndex = terraformIndex;
    }

    public TerraformIndexSyncPacket(FriendlyByteBuf buffer) {
        this(buffer.readLong());
    }

    public static TerraformIndexSyncPacket decode(FriendlyByteBuf buffer) {
        return new TerraformIndexSyncPacket(buffer);
    }

    public long terraformIndex() {
        return terraformIndex;
    }

    public void encode(FriendlyByteBuf buffer) {
        buffer.writeLong(terraformIndex);
    }

    public void handle(Supplier<NetworkEvent.Context> contextSupplier) {
        var context = contextSupplier.get();
        context.enqueueWork(() -> ClientTerraformIndex.setTerraformIndex(terraformIndex));
        context.setPacketHandled(true);
    }
}
