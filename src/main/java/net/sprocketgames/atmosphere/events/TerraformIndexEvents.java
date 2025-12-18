package net.sprocketgames.atmosphere.events;

import java.util.concurrent.ConcurrentLinkedQueue;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.TickEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.sprocketgames.atmosphere.Atmosphere;
import net.sprocketgames.atmosphere.data.TerraformIndexData;
import net.sprocketgames.atmosphere.network.AtmosphereNetwork;

public class TerraformIndexEvents {
    private static final long WATER_PLACE_THRESHOLD = 1_000_000L;
    private static final ConcurrentLinkedQueue<ChunkPos> WATER_STRIP_QUEUE = new ConcurrentLinkedQueue<>();

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

        // Avoid blocking chunk load; queue the chunk for later stripping.
        WATER_STRIP_QUEUE.offer(chunkPos);
        data.markChunkProcessed(chunkKey);
        Atmosphere.LOGGER.debug("Queued water strip for chunk {}", chunkPos);
    }

    public static void onLevelTick(TickEvent.LevelTickEvent event) {
        if (event.level.isClientSide() || event.phase != TickEvent.Phase.END) {
            return;
        }

        if (!(event.level instanceof ServerLevel serverLevel)) {
            return;
        }

        if (serverLevel.dimension() != Level.OVERWORLD) {
            return;
        }

        // Process a few queued chunks per tick to avoid long stalls.
        for (int i = 0; i < 2; i++) {
            ChunkPos chunkPos = WATER_STRIP_QUEUE.poll();
            if (chunkPos == null) {
                break;
            }

            LevelChunk chunk = serverLevel.getChunkSource().getChunkNow(chunkPos.x, chunkPos.z);
            if (chunk == null || chunk.getStatus().isOrAfter(ChunkStatus.FULL) == false) {
                // Requeue if the chunk is still loading.
                WATER_STRIP_QUEUE.offer(chunkPos);
                Atmosphere.LOGGER.debug("Re-queued water strip; chunk {} not ready", chunkPos);
                continue;
            }

            long startNanos = System.nanoTime();
            int removed = clearWaterFromChunk(serverLevel, chunk);
            long durationMs = (System.nanoTime() - startNanos) / 1_000_000L;
            Atmosphere.LOGGER.info("Stripped {} water blocks from chunk {} in {} ms", removed, chunkPos, durationMs);
        }
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

    private static int clearWaterFromChunk(ServerLevel level, LevelChunk chunk) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        int chunkMinX = chunk.getPos().getMinBlockX();
        int chunkMinZ = chunk.getPos().getMinBlockZ();
        int removed = 0;

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
                        level.setBlock(cursor, Blocks.AIR.defaultBlockState(), Block.UPDATE_NONE);
                        removed++;
                    }
                }
            }
        }

        return removed;
    }
}
