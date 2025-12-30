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

        TerraformIndexData data = TerraformIndexData.get(serverLevel);
        long chunkKey = levelChunk.getPos().toLong();
        int waterLevelY = data.getWaterLevelY();
        boolean grassifyEnabled = data.isGrassifyEnabled();
        boolean grassVegEnabled = data.isGrassVegetationEnabled();
        boolean flowerVegEnabled = data.isFlowerVegetationEnabled();
        boolean saplingEnabled = data.isSaplingEnabled();
        boolean needsProcessing = !data.isChunkWaterProcessed(chunkKey, waterLevelY)
            || !data.isChunkGrassProcessed(chunkKey, grassifyEnabled)
            || !data.isChunkVegetationProcessed(chunkKey, grassVegEnabled, flowerVegEnabled)
            || !data.isChunkSaplingProcessed(chunkKey, saplingEnabled);

        TerraformSystem.markLoaded(serverLevel, levelChunk.getPos());

        if (event.isNewChunk()) {
            data.clearChunkState(chunkKey);
            needsProcessing = true;
        }

        if (needsProcessing) {
            TerraformSystem.gateChunkSend(serverLevel, levelChunk.getPos());
            if (shouldProcessImmediately(serverLevel, levelChunk)) {
                TerraformSystem.enqueueImmediate(serverLevel, levelChunk.getPos());
            } else {
                TerraformSystem.enqueue(serverLevel, levelChunk.getPos());
            }
        }
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

    private static boolean shouldProcessImmediately(ServerLevel level, LevelChunk chunk) {
        if (level.players().isEmpty()) {
            return false;
        }

        int viewDistance = Math.max(0, level.getServer().getPlayerList().getViewDistance());
        int chunkX = chunk.getPos().x;
        int chunkZ = chunk.getPos().z;

        for (ServerPlayer player : level.players()) {
            int dx = Math.abs(chunkX - player.chunkPosition().x);
            int dz = Math.abs(chunkZ - player.chunkPosition().z);
            if (Math.max(dx, dz) <= viewDistance) {
                return true;
            }
        }

        return false;
    }
}
