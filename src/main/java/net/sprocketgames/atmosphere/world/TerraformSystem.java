package net.sprocketgames.atmosphere.world;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
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
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.sprocketgames.atmosphere.Atmosphere;
import net.sprocketgames.atmosphere.config.AtmosphereConfig;
import net.sprocketgames.atmosphere.data.TerraformIndexData;

/**
 * Handles throttled terrain updates (water, surface grass, and vegetation) on a chunk-by-chunk basis.
 */
public final class TerraformSystem {
    private static final int MAX_CHUNKS_PER_TICK = 4;
    private static final int[] OFFSETS_X = {1, -1, 0, 0, 0, 0};
    private static final int[] OFFSETS_Y = {0, 0, 1, -1, 0, 0};
    private static final int[] OFFSETS_Z = {0, 0, 0, 0, 1, -1};

    private static final Map<ResourceKey<Level>, ChunkQueue> QUEUES = new HashMap<>();

    private TerraformSystem() {
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
        boolean grassifyEnabled = data.isGrassifyEnabled();
        boolean grassVegEnabled = data.isGrassVegetationEnabled();
        boolean flowerVegEnabled = data.isFlowerVegetationEnabled();
        boolean saplingEnabled = data.isSaplingEnabled();
        long chunkKey = pos.toLong();

        queue.markLoaded(chunkKey);
        if (needsProcessing(data, chunkKey, waterLevel, grassifyEnabled, grassVegEnabled, flowerVegEnabled, saplingEnabled)) {
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

    private static ChunkQueue queueFor(ServerLevel level) {
        return QUEUES.computeIfAbsent(level.dimension(), key -> new ChunkQueue());
    }

    private static boolean needsProcessing(TerraformIndexData data, long chunkKey, int waterLevel,
                                           boolean grassifyEnabled, boolean grassVegEnabled, boolean flowerVegEnabled,
                                           boolean saplingEnabled) {
        return !data.isChunkWaterProcessed(chunkKey, waterLevel)
            || !data.isChunkGrassProcessed(chunkKey, grassifyEnabled)
            || !data.isChunkVegetationProcessed(chunkKey, grassVegEnabled, flowerVegEnabled)
            || !data.isChunkSaplingProcessed(chunkKey, saplingEnabled);
    }

    private static void processQueue(ServerLevel level) {
        ChunkQueue queue = queueFor(level);
        TerraformIndexData data = TerraformIndexData.get(level);
        int waterLevel = data.getWaterLevelY();
        boolean grassifyEnabled = data.isGrassifyEnabled();
        boolean grassVegEnabled = data.isGrassVegetationEnabled();
        boolean flowerVegEnabled = data.isFlowerVegetationEnabled();
        boolean saplingEnabled = data.isSaplingEnabled();

        prioritizePlayerChunks(level, queue, data, waterLevel, grassifyEnabled, grassVegEnabled, flowerVegEnabled, saplingEnabled);

        if (queue.isEmpty()) {
            return;
        }

        int processedChunks = 0;

        while (processedChunks < MAX_CHUNKS_PER_TICK) {
            long chunkKey;
            boolean fromPriority;
            if (queue.hasPriority()) {
                chunkKey = queue.popPriority();
                fromPriority = true;
            } else if (queue.hasNormal()) {
                chunkKey = queue.popNormal();
                fromPriority = false;
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

            boolean waterNeeded = !data.isChunkWaterProcessed(chunkKey, waterLevel);
            boolean grassNeeded = !data.isChunkGrassProcessed(chunkKey, grassifyEnabled);
            boolean vegetationNeeded = !data.isChunkVegetationProcessed(chunkKey, grassVegEnabled, flowerVegEnabled);
            boolean saplingNeeded = !data.isChunkSaplingProcessed(chunkKey, saplingEnabled);
            boolean processedWater = false;
            boolean processedGrass = false;
            boolean processedVegetation = false;
            boolean processedSaplings = false;
            int placed = 0;
            int removed = 0;
            int surfaceChanged = 0;
            VegetationResult vegetationResult = new VegetationResult(0, 0, 0);
            SaplingResult saplingResult = new SaplingResult(0, 0, 0);
            boolean waterUpdated = false;

            if (!waterNeeded && !grassNeeded && !vegetationNeeded && !saplingNeeded) {
                queue.finish(chunkKey);
                processedChunks++;
                continue;
            }

            int previousWaterLevel = data.getProcessedWaterLevel(chunkKey);
            boolean waterLowering = waterNeeded && previousWaterLevel != Integer.MIN_VALUE && waterLevel < previousWaterLevel;

            if (saplingNeeded && !saplingEnabled) {
                SaplingResult result = processSaplingsInChunk(chunk, level, false);
                saplingResult = result;
                processedSaplings = true;
                data.markChunkSaplingProcessed(chunkKey, false);
            }

            if (vegetationNeeded && (!grassVegEnabled || !flowerVegEnabled)) {
                VegetationResult result = processVegetationInChunk(chunk, level, grassVegEnabled, flowerVegEnabled);
                vegetationResult = result;
                processedVegetation = true;
                data.markChunkVegetationProcessed(chunkKey, grassVegEnabled, flowerVegEnabled);
                vegetationNeeded = false;
            }

            if (grassNeeded && !grassifyEnabled) {
                surfaceChanged = transformSurfaceGrassToDirt(chunk, level);
                processedGrass = true;
                data.markChunkGrassProcessed(chunkKey, false);
            }

            if (waterLowering) {
                removed = fastDrainChunk(chunk, waterLevel, level);
                processedWater = true;
                waterUpdated = removed > 0;
                data.markChunkWaterProcessed(chunkKey, waterLevel);
            }

            if (waterNeeded && !waterLowering) {
                boolean allowWaterPlacement = previousWaterLevel == Integer.MIN_VALUE || waterLevel >= previousWaterLevel;
                removed = fastDrainChunk(chunk, waterLevel, level);
                placed = allowWaterPlacement ? fastFillChunk(chunk, waterLevel, level) : 0;
                processedWater = true;
                waterUpdated = removed > 0 || placed > 0;
                data.markChunkWaterProcessed(chunkKey, waterLevel);
            }

            if (grassNeeded && grassifyEnabled) {
                surfaceChanged = transformSurfaceDirtToGrass(chunk, level);
                processedGrass = true;
                data.markChunkGrassProcessed(chunkKey, true);
                if (surfaceChanged > 0 && (grassVegEnabled || flowerVegEnabled)) {
                    vegetationNeeded = true;
                }
            }

            if (vegetationNeeded && (grassVegEnabled || flowerVegEnabled)) {
                VegetationResult result = processVegetationInChunk(chunk, level, grassVegEnabled, flowerVegEnabled);
                vegetationResult = result;
                processedVegetation = true;
                data.markChunkVegetationProcessed(chunkKey, grassVegEnabled, flowerVegEnabled);
                vegetationNeeded = false;
            }

            if (saplingNeeded && saplingEnabled) {
                SaplingResult result = processSaplingsInChunk(chunk, level, true);
                saplingResult = result;
                processedSaplings = true;
                data.markChunkSaplingProcessed(chunkKey, true);
            }

            if (waterUpdated) {
                cleanupSurfaceWater(chunk, level, waterLevel);
                enqueueNeighborChunks(level, data, chunk.getPos());
                refreshChunkLighting(chunk, level);
            }

            if (AtmosphereConfig.DEBUG_LOGGING.get()) {
                List<String> summaries = new ArrayList<>();
                if (processedWater) {
                    StringBuilder waterSummary = new StringBuilder("water placed=")
                        .append(placed)
                        .append(" removed=")
                        .append(removed);
                    summaries.add(waterSummary.toString());
                }
                if (processedGrass) {
                    summaries.add(String.format("surface %s=%d",
                        grassifyEnabled ? "dirt->grass" : "grass->dirt",
                        surfaceChanged));
                }
                if (processedVegetation) {
                    String vegetationSummary = String.format(
                        "vegetation %s=%d (grass=%d, flowers=%d)",
                        (grassVegEnabled || flowerVegEnabled) ? "updated" : "removed",
                        vegetationResult.changed,
                        vegetationResult.grassChanged,
                        vegetationResult.flowerChanged);
                    summaries.add(vegetationSummary);
                }
                if (processedSaplings) {
                    String saplingSummary = String.format(
                        "saplings %s=%d (placed=%d, removed=%d)",
                        saplingEnabled ? "updated" : "removed",
                        saplingResult.changed,
                        saplingResult.placed,
                        saplingResult.removed);
                    summaries.add(saplingSummary);
                }
                if (!summaries.isEmpty()) {
                    Atmosphere.LOGGER.info(
                        "Terraform chunk ({}, {}): {}",
                        chunk.getPos().x,
                        chunk.getPos().z,
                        String.join(", ", summaries));
                }
            }

            queue.finish(chunkKey);
            processedChunks++;
        }
    }

    private static int fastDrainChunk(LevelChunk chunk, int waterLevelY, ServerLevel level) {
        int removed = 0;
        int minSection = chunk.getMinSection();
        int maxSection = chunk.getMaxSection();
        int startSection = Math.max(minSection, SectionPos.blockToSectionCoord(waterLevelY));
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

        for (int sectionY = startSection; sectionY < maxSection; sectionY++) {
            int sectionMinY = SectionPos.sectionToBlockCoord(sectionY);
            int sectionMaxY = sectionMinY + 15;
            if (sectionMaxY <= waterLevelY) {
                continue;
            }

            LevelChunkSection section = chunk.getSection(chunk.getSectionIndexFromSectionY(sectionY));
            if (!section.maybeHas(state -> state.getFluidState().is(FluidTags.WATER) || (state.hasProperty(BlockStateProperties.WATERLOGGED) && state.getValue(BlockStateProperties.WATERLOGGED)))) {
                continue;
            }

            int minLocalY = Math.max(0, waterLevelY - sectionMinY + 1);
            int worldBaseX = chunk.getPos().getMinBlockX();
            int worldBaseZ = chunk.getPos().getMinBlockZ();
            section.acquire();
            try {
                for (int y = minLocalY; y < 16; y++) {
                    int worldY = sectionMinY + y;
                    for (int x = 0; x < 16; x++) {
                        int worldX = worldBaseX + x;
                        for (int z = 0; z < 16; z++) {
                            BlockState state = section.getBlockState(x, y, z);
                            if (state.hasProperty(BlockStateProperties.WATERLOGGED) && state.getValue(BlockStateProperties.WATERLOGGED)) {
                                BlockState cleared = state.setValue(BlockStateProperties.WATERLOGGED, false);
                                section.setBlockState(x, y, z, cleared, false);
                                cursor.set(worldX, worldY, worldBaseZ + z);
                                level.getChunkSource().blockChanged(cursor);
                                level.getChunkSource().getLightEngine().checkBlock(cursor);
                                removed++;
                                continue;
                            }

                            if (!state.getFluidState().is(FluidTags.WATER)) {
                                continue;
                            }

                            section.setBlockState(x, y, z, air, false);
                            cursor.set(worldX, worldY, worldBaseZ + z);
                            level.getChunkSource().blockChanged(cursor);
                            level.getChunkSource().getLightEngine().checkBlock(cursor);
                            removed++;
                        }
                    }
                }
            } finally {
                section.release();
            }
        }

        if (removed > 0) {
            chunk.setUnsaved(true);
        }

        return removed;
    }

    private static int fastFillChunk(LevelChunk chunk, int waterLevelY, ServerLevel level) {
        int placed = 0;
        int minY = level.getMinBuildHeight();
        int maxY = Math.min(waterLevelY, level.getMaxBuildHeight() - 1);
        if (maxY < minY) {
            return 0;
        }

        int height = maxY - minY + 1;
        boolean[] visited = new boolean[16 * 16 * height];
        java.util.ArrayDeque<Integer> queue = new java.util.ArrayDeque<>();
        BlockState water = Blocks.WATER.defaultBlockState();

        int worldBaseX = chunk.getPos().getMinBlockX();
        int worldBaseZ = chunk.getPos().getMinBlockZ();

        for (int x = 0; x < 16; x++) {
            int worldX = worldBaseX + x;
            for (int z = 0; z < 16; z++) {
                int worldZ = worldBaseZ + z;
                BlockPos seedPos = new BlockPos(worldX, maxY, worldZ);
                if (!level.canSeeSky(seedPos)) {
                    continue;
                }

                BlockState seedState = level.getBlockState(seedPos);
                if (!seedState.isAir() && !seedState.is(Blocks.WATER)) {
                    continue;
                }

                int seedIndex = packFloodIndex(x, maxY - minY, z);
                if (!visited[seedIndex]) {
                    visited[seedIndex] = true;
                    queue.add(seedIndex);
                }
            }
        }

        BlockPos.MutableBlockPos borderPos = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos neighborPos = new BlockPos.MutableBlockPos();
        for (int y = minY; y <= maxY; y++) {
            int localY = y - minY;
            for (int x = 0; x < 16; x++) {
                borderPos.set(worldBaseX + x, y, worldBaseZ);
                neighborPos.set(worldBaseX + x, y, worldBaseZ - 1);
                seedFromNeighborWater(level, borderPos, neighborPos, visited, queue, x, localY, 0);

                borderPos.set(worldBaseX + x, y, worldBaseZ + 15);
                neighborPos.set(worldBaseX + x, y, worldBaseZ + 16);
                seedFromNeighborWater(level, borderPos, neighborPos, visited, queue, x, localY, 15);
            }
            for (int z = 0; z < 16; z++) {
                borderPos.set(worldBaseX, y, worldBaseZ + z);
                neighborPos.set(worldBaseX - 1, y, worldBaseZ + z);
                seedFromNeighborWater(level, borderPos, neighborPos, visited, queue, 0, localY, z);

                borderPos.set(worldBaseX + 15, y, worldBaseZ + z);
                neighborPos.set(worldBaseX + 16, y, worldBaseZ + z);
                seedFromNeighborWater(level, borderPos, neighborPos, visited, queue, 15, localY, z);
            }
        }

        while (!queue.isEmpty()) {
            int packed = queue.removeFirst();
            int x = unpackFloodX(packed);
            int z = unpackFloodZ(packed);
            int localY = unpackFloodY(packed);
            int worldY = minY + localY;
            BlockPos pos = new BlockPos(worldBaseX + x, worldY, worldBaseZ + z);
            BlockState state = level.getBlockState(pos);

            if (state.isAir()) {
                int sectionIndex = chunk.getSectionIndex(worldY);
                if (sectionIndex >= 0 && sectionIndex < chunk.getSectionsCount()) {
                    LevelChunkSection section = chunk.getSection(sectionIndex);
                    section.acquire();
                    try {
                        section.setBlockState(x, worldY & 15, z, water, false);
                    } finally {
                        section.release();
                    }
                    level.getChunkSource().blockChanged(pos);
                    level.getChunkSource().getLightEngine().checkBlock(pos);
                    placed++;
                }
            } else if (!state.is(Blocks.WATER)) {
                continue;
            }

            for (int i = 0; i < 6; i++) {
                int nx = x + OFFSETS_X[i];
                int ny = localY + OFFSETS_Y[i];
                int nz = z + OFFSETS_Z[i];

                if (nx < 0 || nx >= 16 || nz < 0 || nz >= 16 || ny < 0 || ny >= height) {
                    continue;
                }

                int neighborIndex = packFloodIndex(nx, ny, nz);
                if (visited[neighborIndex]) {
                    continue;
                }

                neighborPos.set(worldBaseX + nx, minY + ny, worldBaseZ + nz);
                BlockState neighborState = level.getBlockState(neighborPos);
                if (neighborState.isAir() || neighborState.is(Blocks.WATER)) {
                    visited[neighborIndex] = true;
                    queue.add(neighborIndex);
                }
            }
        }

        if (placed > 0) {
            chunk.setUnsaved(true);
        }

        return placed;
    }

    private static void cleanupSurfaceWater(LevelChunk chunk, ServerLevel level, int waterLevelY) {
        int sectionIndex = chunk.getSectionIndex(waterLevelY);
        if (sectionIndex < 0 || sectionIndex >= chunk.getSectionsCount()) {
            return;
        }

        LevelChunkSection section = chunk.getSection(sectionIndex);
        int localY = waterLevelY & 15;
        int worldBaseX = chunk.getPos().getMinBlockX();
        int worldBaseZ = chunk.getPos().getMinBlockZ();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        boolean changed = false;

        section.acquire();
        try {
            for (int x = 0; x < 16; x++) {
                int worldX = worldBaseX + x;
                for (int z = 0; z < 16; z++) {
                    BlockState state = section.getBlockState(x, localY, z);
                    if (state.getFluidState().is(FluidTags.WATER) && !state.getFluidState().isSource()) {
                        section.setBlockState(x, localY, z, Blocks.AIR.defaultBlockState(), false);
                        cursor.set(worldX, waterLevelY, worldBaseZ + z);
                        level.getChunkSource().blockChanged(cursor);
                        level.getChunkSource().getLightEngine().checkBlock(cursor);
                        changed = true;
                    } else if (state.hasProperty(BlockStateProperties.WATERLOGGED)
                        && state.getValue(BlockStateProperties.WATERLOGGED)) {
                        BlockState cleared = state.setValue(BlockStateProperties.WATERLOGGED, false);
                        section.setBlockState(x, localY, z, cleared, false);
                        cursor.set(worldX, waterLevelY, worldBaseZ + z);
                        level.getChunkSource().blockChanged(cursor);
                        level.getChunkSource().getLightEngine().checkBlock(cursor);
                        changed = true;
                    }
                }
            }
        } finally {
            section.release();
        }

        if (changed) {
            chunk.setUnsaved(true);
        }
    }

    private static void enqueueNeighborChunks(ServerLevel level, TerraformIndexData data, ChunkPos pos) {
        int baseX = pos.x;
        int baseZ = pos.z;
        enqueueNeighbor(level, data, new ChunkPos(baseX + 1, baseZ));
        enqueueNeighbor(level, data, new ChunkPos(baseX - 1, baseZ));
        enqueueNeighbor(level, data, new ChunkPos(baseX, baseZ + 1));
        enqueueNeighbor(level, data, new ChunkPos(baseX, baseZ - 1));
    }

    private static void enqueueNeighbor(ServerLevel level, TerraformIndexData data, ChunkPos pos) {
        data.clearChunkWaterProcessed(pos.toLong());
        LevelChunk chunk = level.getChunkSource().getChunkNow(pos.x, pos.z);
        if (chunk != null) {
            enqueue(level, pos);
        }
    }

    private static void seedFromNeighborWater(ServerLevel level, BlockPos borderPos, BlockPos neighborPos,
                                              boolean[] visited, java.util.ArrayDeque<Integer> queue,
                                              int x, int localY, int z) {
        BlockState neighborState = level.getBlockState(neighborPos);
        if (!neighborState.is(Blocks.WATER)) {
            return;
        }

        BlockState currentState = level.getBlockState(borderPos);
        if (!currentState.isAir() && !currentState.is(Blocks.WATER)) {
            return;
        }

        int seedIndex = packFloodIndex(x, localY, z);
        if (!visited[seedIndex]) {
            visited[seedIndex] = true;
            queue.add(seedIndex);
        }
    }

    public static void refreshChunkLighting(LevelChunk chunk, ServerLevel level) {
        var lightEngine = level.getChunkSource().getLightEngine();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        int worldBaseX = chunk.getPos().getMinBlockX();
        int worldBaseZ = chunk.getPos().getMinBlockZ();

        for (int x = 0; x < 16; x++) {
            int worldX = worldBaseX + x;
            for (int z = 0; z < 16; z++) {
                int worldZ = worldBaseZ + z;
                int surfaceY = chunk.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE, x, z);
                int oceanY = chunk.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.OCEAN_FLOOR, x, z);

                cursor.set(worldX, surfaceY, worldZ);
                lightEngine.checkBlock(cursor);
                if (oceanY != surfaceY) {
                    cursor.set(worldX, oceanY, worldZ);
                    lightEngine.checkBlock(cursor);
                }
            }
        }
    }

    private static void prioritizePlayerChunks(ServerLevel level, ChunkQueue queue, TerraformIndexData data, int waterLevel,
                                               boolean grassifyEnabled, boolean grassVegEnabled, boolean flowerVegEnabled,
                                               boolean saplingEnabled) {
        int viewDistance = Math.max(0, level.getServer().getPlayerList().getViewDistance());
        for (ServerPlayer player : level.players()) {
            ChunkPos playerChunk = player.chunkPosition();
            for (int radius = viewDistance; radius >= 0; radius--) {
                for (int dx = -radius; dx <= radius; dx++) {
                    for (int dz = -radius; dz <= radius; dz++) {
                        if (Math.abs(dx) != radius && Math.abs(dz) != radius) {
                            continue;
                        }
                        ChunkPos nearby = new ChunkPos(playerChunk.x + dx, playerChunk.z + dz);
                        long chunkKey = nearby.toLong();
                        if (needsProcessing(data, chunkKey, waterLevel, grassifyEnabled, grassVegEnabled, flowerVegEnabled, saplingEnabled)) {
                            queue.markLoaded(chunkKey);
                            if (!queue.hasTask(chunkKey)) {
                                queue.ensureTask(chunkKey);
                            }
                            queue.prioritize(chunkKey);
                        }
                    }
                }
            }
        }

        for (long chunkKey : queue.loadedKeys()) {
            if (!queue.hasTask(chunkKey)
                && needsProcessing(data, chunkKey, waterLevel, grassifyEnabled, grassVegEnabled, flowerVegEnabled, saplingEnabled)) {
                queue.ensureTask(chunkKey);
            }
        }
    }

    private static int transformSurfaceDirtToGrass(LevelChunk chunk, ServerLevel level) {
        int changed = 0;
        int minSection = chunk.getMinSection();
        int maxSection = chunk.getMaxSection();
        BlockState grass = Blocks.GRASS_BLOCK.defaultBlockState();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

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

    private static VegetationResult processVegetationInChunk(LevelChunk chunk, ServerLevel level, boolean grassVegEnabled, boolean flowerVegEnabled) {
        int changed = 0;
        int grassChanged = 0;
        int flowerChanged = 0;
        java.util.Random random = new java.util.Random();
        BlockState air = Blocks.AIR.defaultBlockState();

        int worldBaseX = chunk.getPos().getMinBlockX();
        int worldBaseZ = chunk.getPos().getMinBlockZ();

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
                        boolean vegetationIsGrassVeg = isGrassVegetation(vegetationState);
                        boolean vegetationIsFlower = !vegetationIsGrassVeg;

                        if (!flowerVegEnabled && vegetationIsFlower) {
                            removeVegetationAt(level, chunk, vegetationPos, vegetationState, air);
                            changed++;
                            flowerChanged++;
                            vegetationState = air;
                        } else if (!grassVegEnabled && vegetationIsGrassVeg) {
                            removeVegetationAt(level, chunk, vegetationPos, vegetationState, air);
                            changed++;
                            grassChanged++;
                            vegetationState = air;
                        }
                    }

                    // Only process grass-based vegetation if we found a grass block
                    if (grassPos != null) {
                        BlockPos abovePos = grassPos.above();
                        // Use level.getBlockState instead of chunk.getBlockState to ensure we read actual world state
                        BlockState above = level.getBlockState(abovePos);
                        if (vegetationPos != null && vegetationPos.equals(abovePos) && vegetationState != null) {
                            above = vegetationState;
                        }
                        boolean aboveIsVegetation = isVegetation(above);
                        boolean aboveIsGrassVeg = isGrassVegetation(above);
                        boolean aboveIsFlower = aboveIsVegetation && !aboveIsGrassVeg;

                        // First priority: Remove unwanted vegetation
                        if (!flowerVegEnabled && aboveIsFlower) {
                            removeVegetationAt(level, chunk, abovePos, above, air);
                            changed++;
                            flowerChanged++;
                            above = air;
                            aboveIsVegetation = false;
                            aboveIsGrassVeg = false;
                            aboveIsFlower = false;
                        }
                        else if (!grassVegEnabled && aboveIsGrassVeg) {
                            removeVegetationAt(level, chunk, abovePos, above, air);
                            changed++;
                            grassChanged++;
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
                                    if (aboveIsGrassVeg) {
                                        grassChanged++;
                                    } else if (aboveIsFlower) {
                                        flowerChanged++;
                                    }
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
                                if (isGrassVegetation(vegetation)) {
                                    grassChanged++;
                                } else {
                                    flowerChanged++;
                                }

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

        if (changed > 0) {
            chunk.setUnsaved(true);
        }

        return new VegetationResult(changed, grassChanged, flowerChanged);
    }

    private static SaplingResult processSaplingsInChunk(LevelChunk chunk, ServerLevel level, boolean saplingEnabled) {
        int changed = 0;
        int placed = 0;
        int removed = 0;
        java.util.Random random = new java.util.Random();
        BlockState air = Blocks.AIR.defaultBlockState();

        int worldBaseX = chunk.getPos().getMinBlockX();
        int worldBaseZ = chunk.getPos().getMinBlockZ();

        try {
            for (int x = 0; x < 16; x++) {
                int worldX = worldBaseX + x;
                for (int z = 0; z < 16; z++) {
                    int worldZ = worldBaseZ + z;
                    int hintY = chunk.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.OCEAN_FLOOR, x, z);
                    BlockPos grassPos = null;
                    BlockPos saplingPos = null;
                    BlockState saplingState = null;
                    BlockPos vegetationPos = null;
                    BlockState vegetationState = null;
                    int minSearchY = Math.max(level.getMinBuildHeight(), hintY - 10);
                    int maxSearchY = Math.min(level.getMaxBuildHeight() - 1, hintY + 5);

                    for (int y = maxSearchY; y >= minSearchY; y--) {
                        BlockPos pos = new BlockPos(worldX, y, worldZ);
                        BlockState state = level.getBlockState(pos);

                        if (state.isAir() || state.is(Blocks.WATER)) {
                            continue;
                        }

                        if (isSapling(state)) {
                            if (saplingPos == null) {
                                saplingPos = pos;
                                saplingState = state;
                            }
                            continue;
                        }

                        if (isVegetation(state)) {
                            if (vegetationPos == null) {
                                vegetationPos = pos;
                                vegetationState = state;
                            }
                            continue;
                        }

                        if (state.is(Blocks.SNOW) || state.is(Blocks.SNOW_BLOCK)) {
                            continue;
                        }

                        if (state.is(Blocks.GRASS_BLOCK)) {
                            grassPos = pos;
                            break;
                        }

                        break;
                    }

                    if (!saplingEnabled && saplingPos != null && saplingState != null) {
                        removeSaplingAt(level, chunk, saplingPos, air);
                        changed++;
                        removed++;
                    }

                    if (saplingEnabled && grassPos != null) {
                        BlockPos abovePos = grassPos.above();
                        BlockState above = level.getBlockState(abovePos);
                        if (saplingPos != null && saplingPos.equals(abovePos) && saplingState != null) {
                            above = saplingState;
                        } else if (vegetationPos != null && vegetationPos.equals(abovePos) && vegetationState != null) {
                            above = vegetationState;
                        }

                        boolean aboveIsSapling = isSapling(above);
                        boolean aboveIsVegetation = isVegetation(above);

                        if (!aboveIsSapling && (above.isAir() || aboveIsVegetation)) {
                            Holder<Biome> biomeHolder = chunk.getNoiseBiome(x >> 2, grassPos.getY() >> 2, z >> 2);
                            random.setSeed((long) worldX * 3129871L ^ (long) worldZ * 116129781L ^ level.getSeed());
                            float chance = random.nextFloat();
                            BlockState sapling = getSaplingForBiome(biomeHolder, chance, random);

                            if (sapling != null) {
                                if (requiresLargeSaplingCluster(sapling)) {
                                    int localX = abovePos.getX() & 15;
                                    int localZ = abovePos.getZ() & 15;
                                    if (localX < 15 && localZ < 15) {
                                        BlockPos eastPos = abovePos.east();
                                        BlockPos southPos = abovePos.south();
                                        BlockPos southEastPos = abovePos.south().east();
                                        BlockPos eastBase = grassPos.east();
                                        BlockPos southBase = grassPos.south();
                                        BlockPos southEastBase = grassPos.south().east();

                                        if (level.getBlockState(eastBase).is(Blocks.GRASS_BLOCK)
                                            && level.getBlockState(southBase).is(Blocks.GRASS_BLOCK)
                                            && level.getBlockState(southEastBase).is(Blocks.GRASS_BLOCK)) {
                                            BlockState eastAbove = level.getBlockState(eastPos);
                                            BlockState southAbove = level.getBlockState(southPos);
                                            BlockState southEastAbove = level.getBlockState(southEastPos);

                                            if (isSapling(eastAbove) || isSapling(southAbove) || isSapling(southEastAbove)) {
                                                continue;
                                            }

                                            if (aboveIsVegetation) {
                                                removeVegetationAt(level, chunk, abovePos, above, air);
                                                changed++;
                                            }
                                            if (isVegetation(eastAbove)) {
                                                removeVegetationAt(level, chunk, eastPos, eastAbove, air);
                                                changed++;
                                            }
                                            if (isVegetation(southAbove)) {
                                                removeVegetationAt(level, chunk, southPos, southAbove, air);
                                                changed++;
                                            }
                                            if (isVegetation(southEastAbove)) {
                                                removeVegetationAt(level, chunk, southEastPos, southEastAbove, air);
                                                changed++;
                                            }

                                            if (isSaplingPlacementEmpty(eastAbove) && isSaplingPlacementEmpty(southAbove)
                                                && isSaplingPlacementEmpty(southEastAbove)) {
                                                placed += placeSaplingAt(level, chunk, abovePos, sapling);
                                                placed += placeSaplingAt(level, chunk, eastPos, sapling);
                                                placed += placeSaplingAt(level, chunk, southPos, sapling);
                                                placed += placeSaplingAt(level, chunk, southEastPos, sapling);
                                                changed += 4;
                                            }
                                        }
                                    }
                                } else {
                                    if (aboveIsVegetation) {
                                        removeVegetationAt(level, chunk, abovePos, above, air);
                                        changed++;
                                    }
                                    if (placeSaplingAt(level, chunk, abovePos, sapling) > 0) {
                                        changed++;
                                        placed++;
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            Atmosphere.LOGGER.error("Error processing saplings in chunk ({}, {}): {}",
                chunk.getPos().x, chunk.getPos().z, e.getMessage(), e);
        }

        if (changed > 0) {
            chunk.setUnsaved(true);
        }

        return new SaplingResult(changed, placed, removed);
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

    private static void removeSaplingAt(ServerLevel level, LevelChunk chunk, BlockPos pos, BlockState air) {
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

    private static boolean isGrassVegetation(BlockState state) {
        return state.is(Blocks.SHORT_GRASS) || state.is(Blocks.TALL_GRASS) ||
               state.is(Blocks.FERN) || state.is(Blocks.LARGE_FERN) ||
               state.is(Blocks.DEAD_BUSH);
    }

    private static boolean isSapling(BlockState state) {
        return state.is(Blocks.OAK_SAPLING) || state.is(Blocks.SPRUCE_SAPLING) ||
               state.is(Blocks.BIRCH_SAPLING) || state.is(Blocks.JUNGLE_SAPLING) ||
               state.is(Blocks.ACACIA_SAPLING) || state.is(Blocks.DARK_OAK_SAPLING) ||
               state.is(Blocks.CHERRY_SAPLING);
    }

    private static boolean isSaplingPlacementEmpty(BlockState state) {
        return state.isAir() || isVegetation(state);
    }

    private static boolean requiresLargeSaplingCluster(BlockState state) {
        return state.is(Blocks.DARK_OAK_SAPLING);
    }

    private static int placeSaplingAt(ServerLevel level, LevelChunk chunk, BlockPos pos, BlockState sapling) {
        int sectionIndex = chunk.getSectionIndex(pos.getY());
        if (sectionIndex < 0 || sectionIndex >= chunk.getSectionsCount()) {
            return 0;
        }
        var section = chunk.getSection(sectionIndex);
        section.acquire();
        try {
            section.setBlockState(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15, sapling, false);
        } finally {
            section.release();
        }
        level.getChunkSource().blockChanged(pos);
        return 1;
    }

    private static BlockState getSaplingForBiome(Holder<Biome> biomeHolder, float chance, java.util.Random random) {
        boolean isForest = biomeHolder.is(BiomeTags.IS_FOREST);
        boolean isTaiga = biomeHolder.is(BiomeTags.IS_TAIGA);
        boolean isJungle = biomeHolder.is(BiomeTags.IS_JUNGLE);
        boolean isSavanna = biomeHolder.is(BiomeTags.IS_SAVANNA);
        boolean isBadlands = biomeHolder.is(BiomeTags.IS_BADLANDS);

        if (isBadlands) {
            return null;
        }

        float density = 0.012f;
        if (isJungle) {
            density = 0.02f;
        } else if (isTaiga) {
            density = 0.016f;
        } else if (isForest) {
            density = 0.018f;
        } else if (isSavanna) {
            density = 0.014f;
        }

        if (chance >= density) {
            return null;
        }

        if (isJungle) {
            return Blocks.JUNGLE_SAPLING.defaultBlockState();
        }
        if (isTaiga) {
            return Blocks.SPRUCE_SAPLING.defaultBlockState();
        }
        if (isSavanna) {
            return Blocks.ACACIA_SAPLING.defaultBlockState();
        }
        if (isForest) {
            float roll = random.nextFloat();
            if (roll < 0.2f) {
                return Blocks.BIRCH_SAPLING.defaultBlockState();
            }
            if (roll < 0.25f) {
                return Blocks.DARK_OAK_SAPLING.defaultBlockState();
            }
            return Blocks.OAK_SAPLING.defaultBlockState();
        }

        return Blocks.OAK_SAPLING.defaultBlockState();
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

    private static int packFloodIndex(int x, int y, int z) {
        return (y << 8) | (z << 4) | x;
    }

    private static int unpackFloodX(int packed) {
        return packed & 15;
    }

    private static int unpackFloodY(int packed) {
        return packed >> 8;
    }

    private static int unpackFloodZ(int packed) {
        return (packed >> 4) & 15;
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

        Iterable<Long> loadedKeys() {
            return loaded;
        }
    }

    private static final class VegetationResult {
        private final int changed;
        private final int grassChanged;
        private final int flowerChanged;

        private VegetationResult(int changed, int grassChanged, int flowerChanged) {
            this.changed = changed;
            this.grassChanged = grassChanged;
            this.flowerChanged = flowerChanged;
        }
    }

    private static final class SaplingResult {
        private final int changed;
        private final int placed;
        private final int removed;

        private SaplingResult(int changed, int placed, int removed) {
            this.changed = changed;
            this.placed = placed;
            this.removed = removed;
        }
    }

    private static final class ChunkWork {
        final ChunkPos pos;

        ChunkWork(int chunkX, int chunkZ) {
            this.pos = new ChunkPos(chunkX, chunkZ);
        }
    }
}
