package net.sprocketgames.atmosphere.world;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.sprocketgames.atmosphere.Atmosphere;
import net.sprocketgames.atmosphere.data.TerraformIndexData;

/**
 * Handles throttled water placement/removal in the Overworld using the global water level.
 */
public final class TerraformWaterSystem {
    private static final int MAX_CHUNKS_PER_TICK = 2;
    private static final int PLAYER_PRIORITY_RADIUS = 2;

    private static final Map<ResourceKey<Level>, ChunkQueue> QUEUES = new HashMap<>();
    private static final boolean LOG_CHUNK_UPDATES = true;

    private TerraformWaterSystem() {
    }

    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel serverLevel)) {
            return;
        }

        if (serverLevel.dimension() != Level.OVERWORLD) {
            return;
        }

        processQueue(serverLevel);
    }

    public static void enqueue(ServerLevel level, ChunkPos pos) {
        ChunkQueue queue = queueFor(level);
        TerraformIndexData data = TerraformIndexData.get(level);
        int waterLevel = data.getWaterLevelY();
        long chunkKey = pos.toLong();

        queue.markLoaded(chunkKey);
        if (!data.isChunkProcessed(chunkKey, waterLevel)) {
            queue.ensureTask(chunkKey);
            queue.prioritize(chunkKey);
        } else if (!queue.hasTask(chunkKey)) {
            queue.ensureTask(chunkKey);
        }

        scheduleProcessedNeighborsForCleanup(queue, data, waterLevel, pos, true);
    }

    public static void unload(ServerLevel level, ChunkPos pos) {
        ChunkQueue queue = queueFor(level);
        queue.drop(pos.toLong());
    }

    public static void requeueLoaded(ServerLevel level) {
        ChunkQueue queue = queueFor(level);
        queue.requeueLoaded();
    }

    private static ChunkQueue queueFor(ServerLevel level) {
        return QUEUES.computeIfAbsent(level.dimension(), key -> new ChunkQueue());
    }

    private static void processQueue(ServerLevel level) {
        ChunkQueue queue = queueFor(level);
        TerraformIndexData data = TerraformIndexData.get(level);
        int waterLevel = data.getWaterLevelY();

        prioritizePlayerChunks(level, queue, data, waterLevel);

        if (queue.isEmpty()) {
            return;
        }

        BlockState air = Blocks.AIR.defaultBlockState();
        BlockState water = Blocks.WATER.defaultBlockState();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        int processedChunks = 0;

        while (processedChunks < MAX_CHUNKS_PER_TICK) {
            long chunkKey;
            if (processedChunks == 0 && queue.hasPriority()) {
                chunkKey = queue.popPriority();
            } else if (queue.hasNormal()) {
                chunkKey = queue.popNormal();
            } else if (queue.hasPriority()) {
                chunkKey = queue.popPriority();
            } else {
                break;
            }

            ChunkWork work = queue.peek(chunkKey);
            if (work == null) {
                continue;
            }

            LevelChunk chunk = level.getChunkSource().getChunkNow(work.pos.x, work.pos.z);
            if (chunk == null) {
                queue.drop(chunkKey);
                continue;
            }

            int placed = 0;
            int removed = 0;
            for (; work.nextColumn < 256; work.nextColumn++) {
                int column = work.nextColumn;

                int localX = column & 15;
                int localZ = (column >>> 4) & 15;
                if (work.cleanupOnly && localX > 0 && localX < 15 && localZ > 0 && localZ < 15) {
                    continue;
                }
                int worldX = chunk.getPos().getMinBlockX() + localX;
                int worldZ = chunk.getPos().getMinBlockZ() + localZ;
                int surfaceY = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, localX, localZ);
                int topY = level.getMaxBuildHeight() - 1;

                for (int y = topY; y >= Math.max(level.getMinBuildHeight(), waterLevel); y--) {
                    cursor.set(worldX, y, worldZ);
                    BlockState state = chunk.getBlockState(cursor);
                    var fluidState = state.getFluidState();
                    boolean isWater = fluidState.is(FluidTags.WATER);
                    boolean isSourceWater = isWater && fluidState.isSource();

                    if (y > waterLevel) {
                        if (isWater) {
                            level.setBlock(cursor, air, Block.UPDATE_ALL);
                            removed++;
                        }
                        continue;
                    }

                    if (isWater) {
                        if (!isSourceWater) {
                            level.setBlock(cursor, water, Block.UPDATE_ALL);
                            placed++;
                        }
                        continue;
                    }

                    if (!state.isAir()) {
                        continue;
                    }

                    level.setBlock(cursor, water, Block.UPDATE_ALL);
                    placed++;
                }
            }

            if (LOG_CHUNK_UPDATES && (placed > 0 || removed > 0)) {
                Atmosphere.LOGGER.debug("Terraform water @ chunk ({}, {}), placed {}, removed {}", chunk.getPos().x, chunk.getPos().z, placed, removed);
            }

            boolean wasInitialPass = !work.cleanupOnly;
            data.markChunkProcessed(chunkKey, waterLevel);
            if (wasInitialPass) {
                scheduleProcessedNeighborsForCleanup(queue, data, waterLevel, work.pos, true);
            }

            queue.finish(chunkKey);

            processedChunks++;
        }
    }

    private static void scheduleProcessedNeighborsForCleanup(ChunkQueue queue, TerraformIndexData data, int waterLevel, ChunkPos pos, boolean prioritize) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }

                long neighborKey = ChunkPos.asLong(pos.x + dx, pos.z + dz);
                if (queue.isLoaded(neighborKey) && data.isChunkProcessed(neighborKey, waterLevel)) {
                    if (!queue.hasTask(neighborKey)) {
                        queue.ensureTask(neighborKey, true);
                    } else {
                        queue.flagCleanup(neighborKey);
                    }
                    if (prioritize) {
                        queue.prioritize(neighborKey);
                    }
                }
            }
        }
    }

    private static void prioritizePlayerChunks(ServerLevel level, ChunkQueue queue, TerraformIndexData data, int waterLevel) {
        for (ServerPlayer player : level.players()) {
            ChunkPos playerChunk = player.chunkPosition();
            for (int dx = -PLAYER_PRIORITY_RADIUS; dx <= PLAYER_PRIORITY_RADIUS; dx++) {
                for (int dz = -PLAYER_PRIORITY_RADIUS; dz <= PLAYER_PRIORITY_RADIUS; dz++) {
                    ChunkPos nearby = new ChunkPos(playerChunk.x + dx, playerChunk.z + dz);
                    long chunkKey = nearby.toLong();
                    if (!data.isChunkProcessed(chunkKey, waterLevel)) {
                        queue.markLoaded(chunkKey);
                        if (queue.hasTask(chunkKey)) {
                            queue.prioritize(chunkKey);
                        } else {
                            queue.ensureTask(chunkKey, false);
                            queue.prioritize(chunkKey);
                        }
                    }
                }
            }
        }
    }

    private static final class ChunkQueue {
        private final Long2ObjectMap<ChunkWork> tasks = new Long2ObjectOpenHashMap<>();
        private final ArrayDeque<Long> priorityOrder = new ArrayDeque<>();
        private final ArrayDeque<Long> normalOrder = new ArrayDeque<>();
        private final LongLinkedOpenHashSet loaded = new LongLinkedOpenHashSet();

        boolean isEmpty() {
            return priorityOrder.isEmpty() && normalOrder.isEmpty();
        }

        void markLoaded(long chunkKey) {
            loaded.add(chunkKey);
        }

        void ensureTask(long chunkKey) {
            ensureTask(chunkKey, false);
        }

        void ensureTask(long chunkKey, boolean cleanupOnly) {
            ChunkWork work = tasks.get(chunkKey);
            if (work == null) {
                tasks.put(chunkKey, new ChunkWork(ChunkPos.getX(chunkKey), ChunkPos.getZ(chunkKey), cleanupOnly));
                normalOrder.add(chunkKey);
            } else if (cleanupOnly && !work.cleanupOnly) {
                work.cleanupOnly = true;
            }
        }

        void drop(long chunkKey) {
            tasks.remove(chunkKey);
            loaded.remove(chunkKey);
            priorityOrder.remove(chunkKey);
            normalOrder.remove(chunkKey);
        }

        void finish(long chunkKey) {
            tasks.remove(chunkKey);
            priorityOrder.remove(chunkKey);
            normalOrder.remove(chunkKey);
        }

        void requeueLoaded() {
            tasks.clear();
            priorityOrder.clear();
            normalOrder.clear();
            for (long chunkKey : loaded) {
                ensureTask(chunkKey, false);
            }
        }

        long popPriority() {
            Long value = priorityOrder.poll();
            return value == null ? 0L : value;
        }

        long popNormal() {
            Long value = normalOrder.poll();
            return value == null ? 0L : value;
        }

        ChunkWork peek(long chunkKey) {
            return tasks.get(chunkKey);
        }

        void prioritize(long chunkKey) {
            if (tasks.containsKey(chunkKey)) {
                if (priorityOrder.remove(chunkKey)) {
                    priorityOrder.addFirst(chunkKey);
                    return;
                }
                if (normalOrder.remove(chunkKey)) {
                    priorityOrder.addFirst(chunkKey);
                } else {
                    priorityOrder.addFirst(chunkKey);
                }
            }
        }

        void flagCleanup(long chunkKey) {
            ChunkWork work = tasks.get(chunkKey);
            if (work != null) {
                work.cleanupOnly = true;
            }
        }

        boolean hasPriority() {
            return !priorityOrder.isEmpty();
        }

        boolean hasNormal() {
            return !normalOrder.isEmpty();
        }

        boolean isLoaded(long chunkKey) {
            return loaded.contains(chunkKey);
        }

        boolean hasTask(long chunkKey) {
            return tasks.containsKey(chunkKey);
        }
    }

    private static final class ChunkWork {
        final ChunkPos pos;
        int nextColumn = 0;
        boolean cleanupOnly;

        ChunkWork(int chunkX, int chunkZ) {
            this(chunkX, chunkZ, false);
        }

        ChunkWork(int chunkX, int chunkZ, boolean cleanupOnly) {
            this.pos = new ChunkPos(chunkX, chunkZ);
            this.cleanupOnly = cleanupOnly;
        }
    }
}
