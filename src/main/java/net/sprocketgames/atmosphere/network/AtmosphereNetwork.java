package net.sprocketgames.atmosphere.network;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.NetworkDirection;
import net.neoforged.neoforge.network.NetworkRegistry;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.simple.SimpleChannel;
import net.sprocketgames.atmosphere.Atmosphere;

/**
 * Central place to register and send all Atmosphere network messages.
 */
public final class AtmosphereNetwork {
    private static final String PROTOCOL_VERSION = "1";
    private static final SimpleChannel CHANNEL = NetworkRegistry.ChannelBuilder
            .named(Atmosphere.id("network"))
            .networkProtocolVersion(() -> PROTOCOL_VERSION)
            .clientAcceptedVersions(PROTOCOL_VERSION::equals)
            .serverAcceptedVersions(PROTOCOL_VERSION::equals)
            .simpleChannel();

    private static int packetIndex = 0;

    private AtmosphereNetwork() {
    }

    public static void register() {
        CHANNEL.messageBuilder(TerraformIndexSyncPacket.class, id(), NetworkDirection.PLAY_TO_CLIENT)
                .encoder(TerraformIndexSyncPacket::encode)
                .decoder(TerraformIndexSyncPacket::decode)
                .consumerMainThread(TerraformIndexSyncPacket::handle)
                .add();
    }

    public static void sendTerraformIndex(ServerPlayer player, long terraformIndex) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new TerraformIndexSyncPacket(terraformIndex));
    }

    private static int id() {
        return packetIndex++;
    }
}
