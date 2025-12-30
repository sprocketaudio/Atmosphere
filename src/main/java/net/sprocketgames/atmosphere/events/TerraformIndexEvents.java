package net.sprocketgames.atmosphere.events;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.sprocketgames.atmosphere.data.TerraformIndexData;
import net.sprocketgames.atmosphere.network.AtmosphereNetwork;
import net.sprocketgames.atmosphere.world.TerraformSystem;

public class TerraformIndexEvents {
    public static void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }

        // Sync the latest Terraform Index to the player as soon as they join the server.
        ServerLevel level = player.serverLevel();
        long terraformIndex = TerraformIndexData.get(level).getTerraformIndex();
        AtmosphereNetwork.sendTerraformIndex(player, terraformIndex);
    }

    public static void onChunkLoad(ChunkEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel serverLevel)) {
            return;
        }

        if (serverLevel.dimension() != Level.OVERWORLD) {
            return;
        }

        if (!(event.getChunk() instanceof LevelChunk levelChunk)) {
            return;
        }

        if (event.isNewChunk()) {
            TerraformSystem.replaceGrassWithDirt(levelChunk, serverLevel);
        }

        TerraformIndexData.get(serverLevel).clearChunkState(levelChunk.getPos().toLong());
        TerraformSystem.refreshChunkLighting(levelChunk, serverLevel);
        TerraformSystem.enqueue(serverLevel, levelChunk.getPos());
    }

    public static void onChunkUnload(ChunkEvent.Unload event) {
        if (!(event.getLevel() instanceof ServerLevel serverLevel)) {
            return;
        }

        if (serverLevel.dimension() != Level.OVERWORLD) {
            return;
        }

        if (!(event.getChunk() instanceof LevelChunk levelChunk)) {
            return;
        }

        TerraformSystem.unload(serverLevel, levelChunk.getPos());
    }
}
