package net.sprocketgames.atmosphere.network;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.sprocketgames.atmosphere.client.ClientTerraformIndex;

@OnlyIn(Dist.CLIENT)
public final class ClientPayloadHandlers {
    private ClientPayloadHandlers() {
    }

    public static void handleTerraformIndexSync(TerraformIndexSyncPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> ClientTerraformIndex.setTerraformIndex(payload.terraformIndex()));
    }
}
