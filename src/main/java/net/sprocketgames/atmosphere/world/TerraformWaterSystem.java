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
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.sprocketgames.atmosphere.data.TerraformIndexData;

/**
 * Handles throttled water placement/removal in the Overworld using the global water level.
 */
public final class TerraformWaterSystem {
    private static final int MAX_CHUNKS_PER_TICK = 2;
    private static final int MAX_COLUMNS_PER_TICK = 96;

    private static final Map<ResourceKey<Level>, ChunkQueue> QUEUES = new HashMap<>();

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
        queue.trackLoaded(pos.toLong());
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
        if (queue.isEmpty()) {
            return;
        }

        BlockState air = Blocks.AIR.defaultBlockState();
        BlockState water = Blocks.WATER.defaultBlockState();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        int minY = level.getMinBuildHeight();
        int processedChunks = 0;
        int processedColumns = 0;
        int waterLevel = TerraformIndexData.get(level).getWaterLevelY();

        while (!queue.isEmpty() && processedChunks < MAX_CHUNKS_PER_TICK && processedColumns < MAX_COLUMNS_PER_TICK) {
            long chunkKey = queue.pop();
            ChunkWork work = queue.peek(chunkKey);
            if (work == null) {
                continue;
            }

            LevelChunk chunk = level.getChunkSource().getChunkNow(work.pos.x, work.pos.z);
            if (chunk == null) {
                queue.drop(chunkKey);
                continue;
            }

            int columnsLeft = Math.min(MAX_COLUMNS_PER_TICK - processedColumns, 256 - work.nextColumn);
            if (columnsLeft <= 0) {
                queue.drop(chunkKey);
                continue;
            }

            for (int i = 0; i < columnsLeft; i++) {
                int column = work.nextColumn++;
                processedColumns++;

                int localX = column & 15;
                int localZ = (column >>> 4) & 15;
                int worldX = chunk.getPos().getMinBlockX() + localX;
                int worldZ = chunk.getPos().getMinBlockZ() + localZ;
                int startY = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, localX, localZ);
                boolean skyClear = true;

                for (int y = startY; y >= minY; y--) {
                    cursor.set(worldX, y, worldZ);
                    if (skyClear && !level.canSeeSkyFromBelowWater(cursor)) {
                        skyClear = false;
                    }

                    BlockState state = chunk.getBlockState(cursor);
                    boolean waterAllowed = skyClear && y <= waterLevel;
                    boolean isWater = state.getFluidState().isSourceOfType(net.minecraft.world.level.material.Fluids.WATER);

                    if (isWater && !waterAllowed) {
                        chunk.setBlockState(cursor, air, false);
                        continue;
                    }

                    if (waterAllowed && state.isAir()) {
                        chunk.setBlockState(cursor, water, false);
                    }
                }
            }

            if (work.nextColumn >= 256) {
                queue.finish(chunkKey);
            } else {
                queue.pushBack(chunkKey);
            }

            processedChunks++;
        }
    }

    private static final class ChunkQueue {
        private final Long2ObjectMap<ChunkWork> tasks = new Long2ObjectOpenHashMap<>();
        private final ArrayDeque<Long> order = new ArrayDeque<>();
        private final LongLinkedOpenHashSet loaded = new LongLinkedOpenHashSet();

        boolean isEmpty() {
            return order.isEmpty();
        }

        void trackLoaded(long chunkKey) {
            loaded.add(chunkKey);
            if (!tasks.containsKey(chunkKey)) {
                tasks.put(chunkKey, new ChunkWork(ChunkPos.getX(chunkKey), ChunkPos.getZ(chunkKey)));
                order.add(chunkKey);
            }
        }

        void drop(long chunkKey) {
            tasks.remove(chunkKey);
            loaded.remove(chunkKey);
            order.remove(chunkKey);
        }

        void finish(long chunkKey) {
            tasks.remove(chunkKey);
            order.remove(chunkKey);
        }

        void requeueLoaded() {
            tasks.clear();
            order.clear();
            for (long chunkKey : loaded) {
                tasks.put(chunkKey, new ChunkWork(ChunkPos.getX(chunkKey), ChunkPos.getZ(chunkKey)));
                order.add(chunkKey);
            }
        }

        long pop() {
            Long value = order.poll();
            return value == null ? 0L : value;
        }

        ChunkWork peek(long chunkKey) {
            return tasks.get(chunkKey);
        }

        void pushBack(long chunkKey) {
            order.add(chunkKey);
        }
    }

    private static final class ChunkWork {
        final ChunkPos pos;
        int nextColumn = 0;

        ChunkWork(int chunkX, int chunkZ) {
            this.pos = new ChunkPos(chunkX, chunkZ);
        }
    }
}
