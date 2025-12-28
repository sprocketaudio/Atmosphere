package net.sprocketgames.atmosphere.world;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.sprocketgames.atmosphere.Atmosphere;
import net.sprocketgames.atmosphere.data.TerraformIndexData;

/**
 * Handles throttled surface dirt-to-grass and grass-to-dirt transformations on a chunk-by-chunk basis.
 */
public final class TerraformSurfaceSystem {
    private static final int MAX_CHUNKS_PER_TICK = 2;
    private static final int PLAYER_PRIORITY_RADIUS = 2;

    private static final Map<ResourceKey<Level>, ChunkQueue> QUEUES = new HashMap<>();
    private static final Map<ResourceKey<Level>, ChunkQueue> VEGETATION_QUEUES = new HashMap<>();
    private static final boolean LOG_CHUNK_UPDATES = true;

    private TerraformSurfaceSystem() {
    }

    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel serverLevel)) {
            return;
        }

        if (serverLevel.dimension() != Level.OVERWORLD) {
            return;
        }

        processQueue(serverLevel);
        processVegetationQueue(serverLevel);
    }

    public static void enqueue(ServerLevel level, ChunkPos pos) {
        ChunkQueue queue = queueFor(level);
        TerraformIndexData data = TerraformIndexData.get(level);
        boolean grassifyEnabled = data.isGrassifyEnabled();
        long chunkKey = pos.toLong();

        queue.markLoaded(chunkKey);
        if (!data.isChunkGrassProcessed(chunkKey, grassifyEnabled)) {
            queue.ensureTask(chunkKey);
            queue.prioritize(chunkKey);
        } else if (!queue.hasTask(chunkKey)) {
            queue.ensureTask(chunkKey);
        }
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
        boolean grassifyEnabled = data.isGrassifyEnabled();

        prioritizePlayerChunks(level, queue, data, grassifyEnabled);

        if (queue.isEmpty()) {
            return;
        }

        int processedChunks = 0;

        while (processedChunks < MAX_CHUNKS_PER_TICK) {
            long chunkKey;
            boolean fromPriority;
            if (processedChunks == 0 && queue.hasPriority()) {
                chunkKey = queue.popPriority();
                fromPriority = true;
            } else if (queue.hasNormal()) {
                chunkKey = queue.popNormal();
                fromPriority = false;
            } else if (queue.hasPriority()) {
                chunkKey = queue.popPriority();
                fromPriority = true;
            } else {
                break;
            }

            ChunkWork work = queue.peek(chunkKey);
            if (work == null) {
                processedChunks++;
                continue;
            }

            LevelChunk chunk = level.getChunkSource().getChunkNow(work.pos.x, work.pos.z);
            if (chunk == null) {
                if (queue.isLoaded(chunkKey)) {
                    queue.requeue(chunkKey, fromPriority);
                } else {
                    queue.drop(chunkKey);
                }
                processedChunks++;
                continue;
            }

            int changed;
            if (grassifyEnabled) {
                changed = transformSurfaceDirtToGrass(chunk, level);
            } else {
                changed = transformSurfaceGrassToDirt(chunk, level);
            }

            if (LOG_CHUNK_UPDATES && changed > 0) {
                Atmosphere.LOGGER.debug(
                        "Terraform surface @ chunk ({}, {}), {} blocks {} -> {}",
                        chunk.getPos().x,
                        chunk.getPos().z,
                        changed,
                        grassifyEnabled ? "dirt" : "grass",
                        grassifyEnabled ? "grass" : "dirt");
            }

            data.markChunkGrassProcessed(chunkKey, grassifyEnabled);
            queue.finish(chunkKey);

            // If we just placed grass blocks, queue this chunk for vegetation processing
            if (grassifyEnabled && changed > 0) {
                enqueueVegetation(level, chunk.getPos());
            }

            processedChunks++;
        }
    }

    private static int transformSurfaceDirtToGrass(LevelChunk chunk, ServerLevel level) {
        int changed = 0;
        int minSection = chunk.getMinSection();
        int maxSection = chunk.getMaxSection();
        BlockState grass = Blocks.GRASS_BLOCK.defaultBlockState();
        BlockState shortGrass = Blocks.SHORT_GRASS.defaultBlockState();
        BlockState tallGrass = Blocks.TALL_GRASS.defaultBlockState();
        BlockState fern = Blocks.FERN.defaultBlockState();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        java.util.Random random = new java.util.Random();

        for (int sectionY = maxSection - 1; sectionY >= minSection; sectionY--) {
            LevelChunkSection section = chunk.getSection(chunk.getSectionIndexFromSectionY(sectionY));
            if (!section.maybeHas(state -> state.is(Blocks.DIRT))) {
                continue;
            }

            int sectionMinY = SectionPos.sectionToBlockCoord(sectionY);
            int worldBaseX = chunk.getPos().getMinBlockX();
            int worldBaseZ = chunk.getPos().getMinBlockZ();
            section.acquire();
            try {
                for (int x = 0; x < 16; x++) {
                    int worldX = worldBaseX + x;
                    for (int z = 0; z < 16; z++) {
                        for (int y = 15; y >= 0; y--) {
                            int worldY = sectionMinY + y;
                            BlockState state = section.getBlockState(x, y, z);

                            if (!state.is(Blocks.DIRT)) {
                                if (!state.isAir() && !state.is(Blocks.WATER)) {
                                    break;
                                }
                                continue;
                            }

                            // Check block above - need to check if exposed to air
                            int aboveY = worldY + 1;
                            int aboveSectionY = SectionPos.blockToSectionCoord(aboveY);

                            // Only convert if block above is air (exposed dirt)
                            if (aboveSectionY >= minSection && aboveSectionY < maxSection) {
                                LevelChunkSection aboveSection = chunk.getSection(chunk.getSectionIndexFromSectionY(aboveSectionY));
                                BlockState above = aboveSection.getBlockState(x, aboveY & 15, z);

                                if (above.isAir()) {
                                    section.setBlockState(x, y, z, grass, false);
                                    cursor.set(worldX, worldY, worldBaseZ + z);
                                    level.getChunkSource().blockChanged(cursor);
                                    level.getChunkSource().getLightEngine().checkBlock(cursor);
                                    changed++;
                                }
                            } else if (aboveSectionY >= maxSection) {
                                // Above world - definitely exposed
                                section.setBlockState(x, y, z, grass, false);
                                cursor.set(worldX, worldY, worldBaseZ + z);
                                level.getChunkSource().blockChanged(cursor);
                                level.getChunkSource().getLightEngine().checkBlock(cursor);
                                changed++;
                            }
                            break;
                        }
                    }
                }
            } finally {
                section.release();
            }
        }

        if (changed > 0) {
            chunk.setUnsaved(true);
        }

        return changed;
    }

    private static int transformSurfaceGrassToDirt(LevelChunk chunk, ServerLevel level) {
        int changed = 0;
        int minSection = chunk.getMinSection();
        int maxSection = chunk.getMaxSection();
        BlockState dirt = Blocks.DIRT.defaultBlockState();
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

        for (int sectionY = maxSection - 1; sectionY >= minSection; sectionY--) {
            LevelChunkSection section = chunk.getSection(chunk.getSectionIndexFromSectionY(sectionY));
            if (!section.maybeHas(state -> state.is(Blocks.GRASS_BLOCK))) {
                continue;
            }

            int sectionMinY = SectionPos.sectionToBlockCoord(sectionY);
            int worldBaseX = chunk.getPos().getMinBlockX();
            int worldBaseZ = chunk.getPos().getMinBlockZ();
            section.acquire();
            try {
                for (int x = 0; x < 16; x++) {
                    int worldX = worldBaseX + x;
                    for (int z = 0; z < 16; z++) {
                        for (int y = 15; y >= 0; y--) {
                            int worldY = sectionMinY + y;
                            BlockState state = section.getBlockState(x, y, z);

                            if (!state.is(Blocks.GRASS_BLOCK)) {
                                if (!state.isAir() && !state.is(Blocks.WATER)) {
                                    break;
                                }
                                continue;
                            }

                            section.setBlockState(x, y, z, dirt, false);
                            cursor.set(worldX, worldY, worldBaseZ + z);
                            level.getChunkSource().blockChanged(cursor);
                            level.getChunkSource().getLightEngine().checkBlock(cursor);
                            changed++;

                            // Note: Vegetation removal is handled by the vegetation system
                            // when grass vegetation is disabled
                            break;
                        }
                    }
                }
            } finally {
                section.release();
            }
        }

        if (changed > 0) {
            chunk.setUnsaved(true);
        }

        return changed;
    }

    public static void replaceGrassWithDirt(LevelChunk chunk, ServerLevel level) {
        // Simple worldgen version - just replace grass with dirt, no vegetation handling
        try {
            int minSection = chunk.getMinSection();
            int maxSection = chunk.getMaxSection();
            BlockState dirt = Blocks.DIRT.defaultBlockState();
            BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
            boolean changed = false;

            for (int sectionY = minSection; sectionY < maxSection; sectionY++) {
                LevelChunkSection section = chunk.getSection(chunk.getSectionIndexFromSectionY(sectionY));
                if (!section.maybeHas(state -> state.is(Blocks.GRASS_BLOCK))) {
                    continue;
                }

                int sectionMinY = SectionPos.sectionToBlockCoord(sectionY);
                int worldBaseX = chunk.getPos().getMinBlockX();
                int worldBaseZ = chunk.getPos().getMinBlockZ();
                section.acquire();
                try {
                    for (int y = 0; y < 16; y++) {
                        int worldY = sectionMinY + y;
                        for (int x = 0; x < 16; x++) {
                            int worldX = worldBaseX + x;
                            for (int z = 0; z < 16; z++) {
                                BlockState state = section.getBlockState(x, y, z);
                                if (!state.is(Blocks.GRASS_BLOCK)) {
                                    continue;
                                }

                                section.setBlockState(x, y, z, dirt, false);
                                cursor.set(worldX, worldY, worldBaseZ + z);
                                level.getChunkSource().blockChanged(cursor);
                                changed = true;
                            }
                        }
                    }
                } finally {
                    section.release();
                }
            }

            if (changed) {
                chunk.setUnsaved(true);
            }
        } catch (Exception e) {
            Atmosphere.LOGGER.error("Error replacing grass with dirt in chunk {}: {}", chunk.getPos(), e.getMessage(), e);
        }
    }

    public static void enqueueVegetation(ServerLevel level, ChunkPos pos) {
        ChunkQueue queue = vegetationQueueFor(level);
        TerraformIndexData data = TerraformIndexData.get(level);
        boolean grassVegEnabled = data.isGrassVegetationEnabled();
        boolean flowerVegEnabled = data.isFlowerVegetationEnabled();
        long chunkKey = pos.toLong();

        queue.markLoaded(chunkKey);
        if (!data.isChunkVegetationProcessed(chunkKey, grassVegEnabled, flowerVegEnabled)) {
            queue.ensureTask(chunkKey);
            queue.prioritize(chunkKey);
        } else if (!queue.hasTask(chunkKey)) {
            queue.ensureTask(chunkKey);
        }
    }

    public static void unloadVegetation(ServerLevel level, ChunkPos pos) {
        ChunkQueue queue = vegetationQueueFor(level);
        queue.drop(pos.toLong());
    }

    public static void requeueLoadedVegetation(ServerLevel level) {
        ChunkQueue vegetationQueue = vegetationQueueFor(level);
        ChunkQueue grassQueue = queueFor(level);

        // Requeue existing vegetation tasks
        vegetationQueue.requeueLoaded();

        // Queue ALL loaded chunks for vegetation processing
        // This includes vanilla chunks that were never processed by our grass system
        // We need to process them to remove vanilla vegetation when disabled
        for (long chunkKey : grassQueue.getLoadedChunks()) {
            vegetationQueue.markLoaded(chunkKey);
            if (!vegetationQueue.hasTask(chunkKey)) {
                vegetationQueue.ensureTask(chunkKey);
                vegetationQueue.prioritize(chunkKey);
            }
        }
    }

    private static ChunkQueue vegetationQueueFor(ServerLevel level) {
        return VEGETATION_QUEUES.computeIfAbsent(level.dimension(), key -> new ChunkQueue());
    }

    private static void processVegetationQueue(ServerLevel level) {
        ChunkQueue queue = vegetationQueueFor(level);
        TerraformIndexData data = TerraformIndexData.get(level);
        boolean grassVegEnabled = data.isGrassVegetationEnabled();
        boolean flowerVegEnabled = data.isFlowerVegetationEnabled();

        prioritizePlayerChunksForVegetation(level, queue, data, grassVegEnabled, flowerVegEnabled);

        if (queue.isEmpty()) {
            if (grassVegEnabled || flowerVegEnabled) {
                Atmosphere.LOGGER.info("Vegetation queue is empty (grassVeg={}, flowers={})", grassVegEnabled, flowerVegEnabled);
            }
            return;
        }

        Atmosphere.LOGGER.info("Processing vegetation queue (grassVeg={}, flowers={}, queue size={})",
            grassVegEnabled, flowerVegEnabled, queue.tasks.size());

        int processedChunks = 0;

        while (processedChunks < MAX_CHUNKS_PER_TICK) {
            long chunkKey;
            boolean fromPriority;
            if (processedChunks == 0 && queue.hasPriority()) {
                chunkKey = queue.popPriority();
                fromPriority = true;
            } else if (queue.hasNormal()) {
                chunkKey = queue.popNormal();
                fromPriority = false;
            } else if (queue.hasPriority()) {
                chunkKey = queue.popPriority();
                fromPriority = true;
            } else {
                break;
            }

            ChunkWork work = queue.peek(chunkKey);
            if (work == null) {
                processedChunks++;
                continue;
            }

            LevelChunk chunk = level.getChunkSource().getChunkNow(work.pos.x, work.pos.z);
            if (chunk == null) {
                if (queue.isLoaded(chunkKey)) {
                    queue.requeue(chunkKey, fromPriority);
                } else {
                    queue.drop(chunkKey);
                }
                processedChunks++;
                continue;
            }

            int changed = processVegetationInChunk(chunk, level, grassVegEnabled, flowerVegEnabled);

            if (LOG_CHUNK_UPDATES && changed > 0) {
                Atmosphere.LOGGER.debug(
                        "Terraform vegetation @ chunk ({}, {}), {} plants {}",
                        chunk.getPos().x,
                        chunk.getPos().z,
                        changed,
                        (grassVegEnabled && flowerVegEnabled) ? "added" : (grassVegEnabled || flowerVegEnabled) ? "added" : "removed");
            }

            data.markChunkVegetationProcessed(chunkKey, grassVegEnabled, flowerVegEnabled);
            queue.finish(chunkKey);

            processedChunks++;
        }
    }

    private static int processVegetationInChunk(LevelChunk chunk, ServerLevel level, boolean grassVegEnabled, boolean flowerVegEnabled) {
        int changed = 0;
        java.util.Random random = new java.util.Random();
        BlockState air = Blocks.AIR.defaultBlockState();

        int worldBaseX = chunk.getPos().getMinBlockX();
        int worldBaseZ = chunk.getPos().getMinBlockZ();

        Atmosphere.LOGGER.info("Processing vegetation in chunk ({}, {}), grassVeg={}, flowers={}",
            chunk.getPos().x, chunk.getPos().z, grassVegEnabled, flowerVegEnabled);

        int grassBlocksFound = 0;
        int airAboveCount = 0;
        int vegetationFound = 0;
        int grassVegFound = 0;
        int flowerFound = 0;

        // Test one position to see what blocks are actually there
        int testX = 8;
        int testZ = 8;
        int testWorldX = worldBaseX + testX;
        int testWorldZ = worldBaseZ + testZ;
        int testHintY = chunk.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.OCEAN_FLOOR, testX, testZ);
        Atmosphere.LOGGER.info("  Test center: worldX={}, worldZ={}, hintY={}", testWorldX, testWorldZ, testHintY);

        // Scan blocks around the hint to see what's there
        for (int y = Math.min(testHintY + 3, level.getMaxBuildHeight() - 1); y >= Math.max(testHintY - 3, level.getMinBuildHeight()); y--) {
            BlockPos testPos = new BlockPos(testWorldX, y, testWorldZ);
            BlockState testState = level.getBlockState(testPos);
            if (!testState.isAir()) {
                Atmosphere.LOGGER.info("    Y={}: {}", y, testState.getBlock().getName().getString());
            }
        }

        try {
            // Process each column in the chunk
            for (int x = 0; x < 16; x++) {
                int worldX = worldBaseX + x;
                for (int z = 0; z < 16; z++) {
                    int worldZ = worldBaseZ + z;

                    // Use OCEAN_FLOOR heightmap as hint (ignores water), then search nearby
                    int hintY = chunk.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.OCEAN_FLOOR, x, z);

                    // Search within 10 blocks of heightmap hint (handles stale heightmaps)
                    BlockPos grassPos = null;
                    BlockPos vegetationPos = null;
                    BlockState vegetationState = null;
                    int minSearchY = Math.max(level.getMinBuildHeight(), hintY - 10);
                    int maxSearchY = Math.min(level.getMaxBuildHeight() - 1, hintY + 5);

                    for (int y = maxSearchY; y >= minSearchY; y--) {
                        BlockPos pos = new BlockPos(worldX, y, worldZ);
                        // Use level.getBlockState to read actual world state, not cached chunk state
                        BlockState state = level.getBlockState(pos);

                        // Skip air and water
                        if (state.isAir() || state.is(Blocks.WATER)) {
                            continue;
                        }

                        // Skip vegetation blocks while searching for ground
                        if (isVegetation(state)) {
                            if (vegetationPos == null) {
                                vegetationPos = pos;
                                vegetationState = state;
                            }
                            continue;
                        }

                        // Skip snow layers/blocks to find actual ground
                        if (state.is(Blocks.SNOW) || state.is(Blocks.SNOW_BLOCK)) {
                            continue;
                        }

                        // Found grass block - this is our target
                        if (state.is(Blocks.GRASS_BLOCK)) {
                            grassPos = pos;
                            break;
                        }

                        // Hit a non-grass solid block - stop searching this column
                        break;
                    }

                    // Track vegetation even when no grass block is found
                    if (vegetationPos != null) {
                        vegetationFound++;
                        boolean vegetationIsGrassVeg = vegetationState.is(Blocks.SHORT_GRASS) || vegetationState.is(Blocks.TALL_GRASS) ||
                            vegetationState.is(Blocks.FERN) || vegetationState.is(Blocks.LARGE_FERN) || vegetationState.is(Blocks.DEAD_BUSH);
                        boolean vegetationIsFlower = !vegetationIsGrassVeg;
                        if (vegetationIsGrassVeg) {
                            grassVegFound++;
                        }
                        if (vegetationIsFlower) {
                            flowerFound++;
                        }

                        if (!grassVegEnabled && vegetationIsGrassVeg) {
                            removeVegetationAt(level, chunk, vegetationPos, vegetationState, air);
                            changed++;
                            vegetationState = air;
                        } else if (!flowerVegEnabled && vegetationIsFlower) {
                            removeVegetationAt(level, chunk, vegetationPos, vegetationState, air);
                            changed++;
                            vegetationState = air;
                        }
                    }

                    // Only process grass-based vegetation if we found a grass block
                    if (grassPos != null) {
                        grassBlocksFound++;
                        BlockPos abovePos = grassPos.above();
                        // Use level.getBlockState instead of chunk.getBlockState to ensure we read actual world state
                        BlockState above = level.getBlockState(abovePos);
                        if (vegetationPos != null && vegetationPos.equals(abovePos) && vegetationState != null) {
                            above = vegetationState;
                        }
                        if (above.isAir()) {
                            airAboveCount++;
                        }

                        boolean aboveIsVegetation = isVegetation(above);
                        boolean aboveIsGrassVeg = above.is(Blocks.SHORT_GRASS) || above.is(Blocks.TALL_GRASS) ||
                                                   above.is(Blocks.FERN) || above.is(Blocks.LARGE_FERN) || above.is(Blocks.DEAD_BUSH);
                        boolean aboveIsFlower = aboveIsVegetation && !aboveIsGrassVeg;

                        if (x == 8 && z == 8) {
                            Atmosphere.LOGGER.info("    Center: grassPos={}, above={}, isVeg={}, isGrassVeg={}, isFlower={}, willRemove={}",
                                grassPos, above.getBlock().getName().getString(), aboveIsVegetation, aboveIsGrassVeg, aboveIsFlower,
                                (!grassVegEnabled && aboveIsGrassVeg) || (!flowerVegEnabled && aboveIsFlower));
                        }

                        // First priority: Remove unwanted vegetation
                        if (!grassVegEnabled && aboveIsGrassVeg) {
                            removeVegetationAt(level, chunk, abovePos, above, air);
                            changed++;
                            above = air;
                            aboveIsVegetation = false;
                            aboveIsGrassVeg = false;
                            aboveIsFlower = false;
                        }
                        else if (!flowerVegEnabled && aboveIsFlower) {
                            removeVegetationAt(level, chunk, abovePos, above, air);
                            changed++;
                            above = air;
                            aboveIsVegetation = false;
                            aboveIsGrassVeg = false;
                            aboveIsFlower = false;
                        }
                        // Second priority: Add vegetation where enabled
                        else if ((grassVegEnabled || flowerVegEnabled) && (above.isAir() || (!flowerVegEnabled && aboveIsFlower))) {
                            // Get biome from chunk's biome container (thread-safe, no world access needed)
                            Holder<Biome> biomeHolder = chunk.getNoiseBiome(x >> 2, grassPos.getY() >> 2, z >> 2);
                            random.setSeed((long) worldX * 3129871L ^ (long) worldZ * 116129781L ^ level.getSeed());
                            float chance = random.nextFloat();
                            float flowerChance = random.nextFloat();

                            BlockState vegetation = getVegetationForBiome(biomeHolder, chance, flowerChance, random, grassVegEnabled, flowerVegEnabled);

                            if (vegetation != null) {
                                // Remove existing vegetation if replacing
                                if (aboveIsVegetation) {
                                    removeVegetationAt(level, chunk, abovePos, above, air);
                                    changed++;
                                }

                                // Use the same approach as water system - direct section manipulation
                                int sectionIndex = chunk.getSectionIndex(abovePos.getY());
                                if (sectionIndex >= 0 && sectionIndex < chunk.getSectionsCount()) {
                                    var section = chunk.getSection(sectionIndex);
                                    section.acquire();
                                    try {
                                        int localX = abovePos.getX() & 15;
                                        int localY = abovePos.getY() & 15;
                                        int localZ = abovePos.getZ() & 15;
                                        section.setBlockState(localX, localY, localZ, vegetation, false);
                                    } finally {
                                        section.release();
                                    }
                                    level.getChunkSource().blockChanged(abovePos);
                                    changed++;

                                    // Handle double-height plants
                                    if (vegetation.is(Blocks.TALL_GRASS) || vegetation.is(Blocks.LARGE_FERN) ||
                                        vegetation.is(Blocks.SUNFLOWER) || vegetation.is(Blocks.LILAC) ||
                                        vegetation.is(Blocks.ROSE_BUSH) || vegetation.is(Blocks.PEONY)) {
                                        BlockPos aboveAbove = abovePos.above();
                                        int sectionIndex2 = chunk.getSectionIndex(aboveAbove.getY());
                                        if (sectionIndex2 >= 0 && sectionIndex2 < chunk.getSectionsCount()) {
                                            var section2 = chunk.getSection(sectionIndex2);
                                            if (section2.getBlockState(aboveAbove.getX() & 15, aboveAbove.getY() & 15, aboveAbove.getZ() & 15).isAir()) {
                                                BlockState upperHalf = vegetation.setValue(net.minecraft.world.level.block.DoublePlantBlock.HALF, net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER);
                                                section2.acquire();
                                                try {
                                                    section2.setBlockState(aboveAbove.getX() & 15, aboveAbove.getY() & 15, aboveAbove.getZ() & 15, upperHalf, false);
                                                } finally {
                                                    section2.release();
                                                }
                                                level.getChunkSource().blockChanged(aboveAbove);
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            Atmosphere.LOGGER.error("Error processing vegetation in chunk ({}, {}): {}",
                chunk.getPos().x, chunk.getPos().z, e.getMessage(), e);
        }

        Atmosphere.LOGGER.info("  Chunk summary: grassBlocks={}, airAbove={}, vegetation={} (grassVeg={}, flowers={}), changed={}",
            grassBlocksFound, airAboveCount, vegetationFound, grassVegFound, flowerFound, changed);

        if (changed > 0) {
            chunk.setUnsaved(true);
        }

        return changed;
    }

    private static void removeVegetationAt(ServerLevel level, LevelChunk chunk, BlockPos pos, BlockState state, BlockState air) {
        // Use section manipulation like water system
        int sectionIndex = chunk.getSectionIndex(pos.getY());
        if (sectionIndex >= 0 && sectionIndex < chunk.getSectionsCount()) {
            var section = chunk.getSection(sectionIndex);
            section.acquire();
            try {
                section.setBlockState(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15, air, false);
            } finally {
                section.release();
            }
        }
        level.getChunkSource().blockChanged(pos);

        // Remove double-height plants
        if (state.is(Blocks.TALL_GRASS) || state.is(Blocks.LARGE_FERN) ||
            state.is(Blocks.SUNFLOWER) || state.is(Blocks.LILAC) ||
            state.is(Blocks.ROSE_BUSH) || state.is(Blocks.PEONY)) {
            BlockPos aboveAbove = pos.above();
            BlockState aboveAbove2 = level.getBlockState(aboveAbove);
            if (isVegetation(aboveAbove2)) {
                int sectionIndex2 = chunk.getSectionIndex(aboveAbove.getY());
                if (sectionIndex2 >= 0 && sectionIndex2 < chunk.getSectionsCount()) {
                    var section2 = chunk.getSection(sectionIndex2);
                    section2.acquire();
                    try {
                        section2.setBlockState(aboveAbove.getX() & 15, aboveAbove.getY() & 15, aboveAbove.getZ() & 15, air, false);
                    } finally {
                        section2.release();
                    }
                }
                level.getChunkSource().blockChanged(aboveAbove);
            }
        }
    }

    private static void prioritizePlayerChunksForVegetation(ServerLevel level, ChunkQueue queue, TerraformIndexData data, boolean grassVegEnabled, boolean flowerVegEnabled) {
        for (ServerPlayer player : level.players()) {
            ChunkPos playerChunk = player.chunkPosition();
            Atmosphere.LOGGER.info("  Player at chunk ({}, {}), prioritizing nearby chunks", playerChunk.x, playerChunk.z);
            int prioritized = 0;
            for (int dx = -PLAYER_PRIORITY_RADIUS; dx <= PLAYER_PRIORITY_RADIUS; dx++) {
                for (int dz = -PLAYER_PRIORITY_RADIUS; dz <= PLAYER_PRIORITY_RADIUS; dz++) {
                    ChunkPos nearby = new ChunkPos(playerChunk.x + dx, playerChunk.z + dz);
                    long chunkKey = nearby.toLong();

                    // Only prioritize chunks that haven't been processed with current settings
                    if (!data.isChunkVegetationProcessed(chunkKey, grassVegEnabled, flowerVegEnabled)) {
                        queue.markLoaded(chunkKey);
                        if (queue.hasTask(chunkKey)) {
                            queue.prioritize(chunkKey);
                            prioritized++;
                        } else {
                            queue.ensureTask(chunkKey);
                            queue.prioritize(chunkKey);
                            prioritized++;
                        }
                    }
                }
            }
            Atmosphere.LOGGER.info("  Prioritized {} chunks around player", prioritized);
        }
    }

    /**
     * Checks if a block state is vegetation that should be removed/managed.
     */
    private static boolean isVegetation(BlockState state) {
        return state.is(Blocks.SHORT_GRASS) || state.is(Blocks.TALL_GRASS) ||
               state.is(Blocks.FERN) || state.is(Blocks.LARGE_FERN) ||
               state.is(Blocks.DEAD_BUSH) ||
               // Common flowers
               state.is(Blocks.DANDELION) || state.is(Blocks.POPPY) ||
               state.is(Blocks.BLUE_ORCHID) || state.is(Blocks.ALLIUM) ||
               state.is(Blocks.AZURE_BLUET) || state.is(Blocks.OXEYE_DAISY) ||
               state.is(Blocks.CORNFLOWER) || state.is(Blocks.LILY_OF_THE_VALLEY) ||
               // Tulips
               state.is(Blocks.RED_TULIP) || state.is(Blocks.ORANGE_TULIP) ||
               state.is(Blocks.WHITE_TULIP) || state.is(Blocks.PINK_TULIP) ||
               // Double-height flowers
               state.is(Blocks.SUNFLOWER) || state.is(Blocks.LILAC) ||
               state.is(Blocks.ROSE_BUSH) || state.is(Blocks.PEONY);
    }

    /**
     * Returns appropriate vegetation for the given biome with vanilla-like distribution.
     */
    private static BlockState getVegetationForBiome(Holder<Biome> biomeHolder, float chance, float flowerChance, java.util.Random random, boolean grassEnabled, boolean flowersEnabled) {
        // Check biome tags for categorization
        boolean isForest = biomeHolder.is(BiomeTags.IS_FOREST);
        boolean isTaiga = biomeHolder.is(BiomeTags.IS_TAIGA);
        boolean isJungle = biomeHolder.is(BiomeTags.IS_JUNGLE);
        boolean isSavanna = biomeHolder.is(BiomeTags.IS_SAVANNA);
        boolean isBadlands = biomeHolder.is(BiomeTags.IS_BADLANDS);
        boolean isPlains = biomeHolder.is(BiomeTags.IS_HILL) || !isForest && !isTaiga && !isJungle && !isSavanna && !isBadlands;

        // Jungle biomes - dense vegetation with ferns
        if (isJungle) {
            if (grassEnabled && chance < 0.60f) { // 60% short grass
                return Blocks.SHORT_GRASS.defaultBlockState();
            } else if (grassEnabled && chance < 0.75f) { // 15% fern
                return Blocks.FERN.defaultBlockState();
            } else if (grassEnabled && chance < 0.78f) { // 3% large fern
                return Blocks.LARGE_FERN.defaultBlockState().setValue(net.minecraft.world.level.block.DoublePlantBlock.HALF, net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER);
            }
            return null;
        }

        // Taiga biomes - ferns and reduced flowers
        if (isTaiga) {
            if (grassEnabled && chance < 0.40f) { // 40% short grass
                return Blocks.SHORT_GRASS.defaultBlockState();
            } else if (grassEnabled && chance < 0.52f) { // 12% fern
                return Blocks.FERN.defaultBlockState();
            } else if (grassEnabled && chance < 0.54f) { // 2% tall grass
                return Blocks.TALL_GRASS.defaultBlockState().setValue(net.minecraft.world.level.block.DoublePlantBlock.HALF, net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER);
            } else if (grassEnabled && chance < 0.55f) { // 1% large fern
                return Blocks.LARGE_FERN.defaultBlockState().setValue(net.minecraft.world.level.block.DoublePlantBlock.HALF, net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER);
            }
            return null;
        }

        // Forest biomes - mix of grass and flowers
        if (isForest) {
            if (grassEnabled && chance < 0.35f) { // 35% short grass
                return Blocks.SHORT_GRASS.defaultBlockState();
            } else if (grassEnabled && chance < 0.38f) { // 3% tall grass
                return Blocks.TALL_GRASS.defaultBlockState().setValue(net.minecraft.world.level.block.DoublePlantBlock.HALF, net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER);
            } else if (grassEnabled && chance < 0.43f) { // 5% fern
                return Blocks.FERN.defaultBlockState();
            } else if (flowersEnabled && chance < 0.45f && flowerChance < 0.5f) { // 1% flowers
                return getRandomFlower(random, true);
            }
            return null;
        }

        // Savanna biomes - sparse tall grass
        if (isSavanna) {
            if (grassEnabled && chance < 0.25f) { // 25% short grass
                return Blocks.SHORT_GRASS.defaultBlockState();
            } else if (grassEnabled && chance < 0.32f) { // 7% tall grass
                return Blocks.TALL_GRASS.defaultBlockState().setValue(net.minecraft.world.level.block.DoublePlantBlock.HALF, net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER);
            }
            return null;
        }

        // Badlands - very sparse, dead bush on sand
        if (isBadlands) {
            if (grassEnabled && chance < 0.02f) { // 2% dead bush (will only place if actually on sand)
                return Blocks.DEAD_BUSH.defaultBlockState();
            }
            return null;
        }

        // Plains and default biomes - lots of grass and flowers
        if (grassEnabled && chance < 0.40f) { // 40% short grass
            return Blocks.SHORT_GRASS.defaultBlockState();
        } else if (grassEnabled && chance < 0.45f) { // 5% tall grass
            return Blocks.TALL_GRASS.defaultBlockState().setValue(net.minecraft.world.level.block.DoublePlantBlock.HALF, net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER);
        } else if (grassEnabled && chance < 0.47f) { // 2% fern
            return Blocks.FERN.defaultBlockState();
        } else if (flowersEnabled && chance < 0.53f) { // 6% flowers
            return getRandomFlower(random, false);
        }

        return null;
    }

    /**
     * Returns a random flower with vanilla-like distribution.
     */
    private static BlockState getRandomFlower(java.util.Random random, boolean forestBiased) {
        float flowerType = random.nextFloat();

        if (forestBiased) {
            // Forest flowers - lilac, rose bush, peony, lily of the valley
            if (flowerType < 0.25f) {
                return Blocks.LILAC.defaultBlockState().setValue(net.minecraft.world.level.block.DoublePlantBlock.HALF, net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER);
            } else if (flowerType < 0.40f) {
                return Blocks.LILY_OF_THE_VALLEY.defaultBlockState();
            } else if (flowerType < 0.55f) {
                return Blocks.ROSE_BUSH.defaultBlockState().setValue(net.minecraft.world.level.block.DoublePlantBlock.HALF, net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER);
            } else if (flowerType < 0.70f) {
                return Blocks.PEONY.defaultBlockState().setValue(net.minecraft.world.level.block.DoublePlantBlock.HALF, net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER);
            } else {
                return Blocks.POPPY.defaultBlockState();
            }
        }

        // Plains flowers - common variety
        if (flowerType < 0.15f) {
            return Blocks.DANDELION.defaultBlockState();
        } else if (flowerType < 0.30f) {
            return Blocks.POPPY.defaultBlockState();
        } else if (flowerType < 0.40f) {
            return Blocks.AZURE_BLUET.defaultBlockState();
        } else if (flowerType < 0.50f) {
            return Blocks.OXEYE_DAISY.defaultBlockState();
        } else if (flowerType < 0.58f) {
            return Blocks.CORNFLOWER.defaultBlockState();
        } else if (flowerType < 0.70f) {
            return Blocks.SUNFLOWER.defaultBlockState().setValue(net.minecraft.world.level.block.DoublePlantBlock.HALF, net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER);
        } else if (flowerType < 0.80f) {
            // Tulips
            float tulipType = random.nextFloat();
            if (tulipType < 0.25f) {
                return Blocks.RED_TULIP.defaultBlockState();
            } else if (tulipType < 0.50f) {
                return Blocks.ORANGE_TULIP.defaultBlockState();
            } else if (tulipType < 0.75f) {
                return Blocks.WHITE_TULIP.defaultBlockState();
            } else {
                return Blocks.PINK_TULIP.defaultBlockState();
            }
        } else if (flowerType < 0.88f) {
            return Blocks.BLUE_ORCHID.defaultBlockState();
        } else {
            return Blocks.ALLIUM.defaultBlockState();
        }
    }

    private static void prioritizePlayerChunks(ServerLevel level, ChunkQueue queue, TerraformIndexData data, boolean grassifyEnabled) {
        for (ServerPlayer player : level.players()) {
            ChunkPos playerChunk = player.chunkPosition();
            for (int dx = -PLAYER_PRIORITY_RADIUS; dx <= PLAYER_PRIORITY_RADIUS; dx++) {
                for (int dz = -PLAYER_PRIORITY_RADIUS; dz <= PLAYER_PRIORITY_RADIUS; dz++) {
                    ChunkPos nearby = new ChunkPos(playerChunk.x + dx, playerChunk.z + dz);
                    long chunkKey = nearby.toLong();
                    if (!data.isChunkGrassProcessed(chunkKey, grassifyEnabled)) {
                        queue.markLoaded(chunkKey);
                        if (queue.hasTask(chunkKey)) {
                            queue.prioritize(chunkKey);
                        } else {
                            queue.ensureTask(chunkKey);
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
            ChunkWork work = tasks.get(chunkKey);
            if (work == null) {
                tasks.put(chunkKey, new ChunkWork(ChunkPos.getX(chunkKey), ChunkPos.getZ(chunkKey)));
                normalOrder.add(chunkKey);
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
                ensureTask(chunkKey);
            }
        }

        LongLinkedOpenHashSet getLoadedChunks() {
            return loaded;
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

        void requeue(long chunkKey, boolean priority) {
            if (!tasks.containsKey(chunkKey)) {
                return;
            }
            if (priority) {
                priorityOrder.remove(chunkKey);
                priorityOrder.addLast(chunkKey);
            } else {
                normalOrder.remove(chunkKey);
                normalOrder.addLast(chunkKey);
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

        ChunkWork(int chunkX, int chunkZ) {
            this.pos = new ChunkPos(chunkX, chunkZ);
        }
    }
}
