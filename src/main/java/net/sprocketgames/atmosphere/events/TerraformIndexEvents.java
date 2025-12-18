package net.sprocketgames.atmosphere.events;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.sprocketgames.atmosphere.Atmosphere;
import net.sprocketgames.atmosphere.data.TerraformIndexData;
import net.sprocketgames.atmosphere.network.AtmosphereNetwork;

public class TerraformIndexEvents {
    private static final long WATER_PLACE_THRESHOLD = 1_000_000L;

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

        ChunkPos chunkPos = levelChunk.getPos();
        long chunkKey = chunkPos.toLong();
        TerraformIndexData data = TerraformIndexData.get(serverLevel);
        if (data.isChunkProcessed(chunkKey)) {
            return;
        }

        // Ensure chunk mutation occurs on the main server thread to avoid worldgen deadlocks.
        serverLevel.getServer().execute(() -> {
            if (!serverLevel.hasChunk(chunkPos.x, chunkPos.z)) {
                return;
            }

            LevelChunk liveChunk = serverLevel.getChunk(chunkPos.x, chunkPos.z);
            // Strip naturally generated water when an Overworld chunk is first loaded.
            clearWaterFromChunk(serverLevel, liveChunk);
            data.markChunkProcessed(chunkKey);
        });
    }

    public static void onWaterPlaced(BlockEvent.EntityPlaceEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }

        if (!(event.getLevel() instanceof ServerLevel serverLevel)) {
            return;
        }

        if (serverLevel.dimension() != Level.OVERWORLD) {
            return;
        }

        BlockState placedState = event.getPlacedBlock();
        if (!placedState.getFluidState().is(FluidTags.WATER)) {
            return;
        }

        long terraformIndex = TerraformIndexData.get(serverLevel).getTerraformIndex();
        if (terraformIndex < WATER_PLACE_THRESHOLD) {
            // Player water placement is blocked until the Terraform Index reaches the threshold.
            serverLevel.setBlock(event.getPos(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
    }

    private static void clearWaterFromChunk(ServerLevel level, LevelChunk chunk) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        int chunkMinX = chunk.getPos().getMinBlockX();
        int chunkMinZ = chunk.getPos().getMinBlockZ();

        LevelChunkSection[] sections = chunk.getSections();
        for (int sectionIndex = 0; sectionIndex < sections.length; sectionIndex++) {
            LevelChunkSection section = sections[sectionIndex];
            if (section == null || section.hasOnlyAir()) {
                continue;
            }

            PalettedContainer<BlockState> states = section.getStates();
            int sectionY = chunk.getSectionYFromSectionIndex(sectionIndex);
            int sectionMinY = SectionPos.sectionToBlockCoord(sectionY);

            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        BlockState state = states.get(x, y, z);
                        if (!state.getFluidState().is(FluidTags.WATER)) {
                            continue;
                        }

                        cursor.set(chunkMinX + x, sectionMinY + y, chunkMinZ + z);
                        level.setBlock(cursor, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                    }
                }
            }
        }
    }
}
