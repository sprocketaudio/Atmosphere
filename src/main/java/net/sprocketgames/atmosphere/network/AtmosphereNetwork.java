package net.sprocketgames.atmosphere.network;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import net.sprocketgames.atmosphere.Atmosphere;

/**
 * Central place to register and send all Atmosphere network messages.
 */
public final class AtmosphereNetwork {
    private static final String PROTOCOL_VERSION = "1";

    private AtmosphereNetwork() {
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(PROTOCOL_VERSION);

        if (FMLEnvironment.dist.isClient()) {
            registrar.playToClient(
                    TerraformIndexSyncPayload.TYPE,
                    TerraformIndexSyncPayload.STREAM_CODEC,
                    ClientPayloadHandlers::handleTerraformIndexSync);
        } else {
            // Server still registers the payload so it can send it to clients.
            registrar.playToClient(TerraformIndexSyncPayload.TYPE, TerraformIndexSyncPayload.STREAM_CODEC, (payload, context) -> {
            });
        }
    }

    public static void sendTerraformIndex(ServerPlayer player, long terraformIndex) {
        PacketDistributor.sendToPlayer(player, new TerraformIndexSyncPayload(terraformIndex));
    }
}
