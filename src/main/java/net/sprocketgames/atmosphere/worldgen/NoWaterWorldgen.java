package net.sprocketgames.atmosphere.worldgen;

import java.util.EnumSet;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.SectionPos;
import net.minecraft.tags.BiomeTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;

final class NoWaterWorldgen {
    private static final int SURFACE_DEPTH = 3;
    private static final int BEACH_DEPTH = 2;
    private static final int OCEAN_FLOOR_DEPTH = 4;

    private NoWaterWorldgen() {
    }

    static Holder<NoiseGeneratorSettings> createNoWaterSettings(Holder<NoiseGeneratorSettings> baseSettings) {
        NoiseGeneratorSettings base = baseSettings.value();
        NoiseGeneratorSettings adjusted = new NoiseGeneratorSettings(
            base.noiseSettings(),
            base.defaultBlock(),
            Blocks.AIR.defaultBlockState(),
            base.noiseRouter(),
            base.surfaceRule(),
            base.spawnTarget(),
            base.seaLevel(),
            base.disableMobGeneration(),
            false,
            base.oreVeinsEnabled(),
            base.useLegacyRandomSource()
        );
        return Holder.direct(adjusted);
    }

    static void applySurfacePass(WorldGenLevel level, ChunkAccess chunk, int seaLevel) {
        int removed = removeWaterBlocks(chunk);
        int surfaceChanged = applyVirtualSeaLevelSurface(level, chunk, seaLevel);
        if (removed > 0 || surfaceChanged > 0) {
            Heightmap.primeHeightmaps(chunk, EnumSet.of(Heightmap.Types.WORLD_SURFACE, Heightmap.Types.OCEAN_FLOOR));
        }
    }

    static void stripWaterFeatures(WorldGenLevel level, ChunkAccess chunk) {
        int removed = removeWaterBlocks(chunk);
        if (removed > 0) {
            Heightmap.primeHeightmaps(chunk, EnumSet.of(Heightmap.Types.WORLD_SURFACE, Heightmap.Types.OCEAN_FLOOR));
        }
    }

    private static int removeWaterBlocks(ChunkAccess chunk) {
        int removed = 0;
        BlockState air = Blocks.AIR.defaultBlockState();
        int minSection = chunk.getMinSection();
        int maxSection = chunk.getMaxSection();

        for (int sectionY = minSection; sectionY < maxSection; sectionY++) {
            LevelChunkSection section = chunk.getSection(chunk.getSectionIndexFromSectionY(sectionY));
            if (!section.maybeHas(state -> state.getFluidState().is(FluidTags.WATER)
                || (state.hasProperty(BlockStateProperties.WATERLOGGED) && state.getValue(BlockStateProperties.WATERLOGGED)))) {
                continue;
            }

            int sectionMinY = SectionPos.sectionToBlockCoord(sectionY);
            section.acquire();
            try {
                for (int y = 0; y < 16; y++) {
                    for (int x = 0; x < 16; x++) {
                        for (int z = 0; z < 16; z++) {
                            BlockState state = section.getBlockState(x, y, z);
                            if (state.hasProperty(BlockStateProperties.WATERLOGGED)
                                && state.getValue(BlockStateProperties.WATERLOGGED)) {
                                BlockState cleared = state.setValue(BlockStateProperties.WATERLOGGED, false);
                                section.setBlockState(x, y, z, cleared, false);
                                removed++;
                            } else if (state.getFluidState().is(FluidTags.WATER)) {
                                section.setBlockState(x, y, z, air, false);
                                removed++;
                            }
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

    private static int applyVirtualSeaLevelSurface(WorldGenLevel level, ChunkAccess chunk, int seaLevel) {
        int changed = 0;
        int worldBaseX = chunk.getPos().getMinBlockX();
        int worldBaseZ = chunk.getPos().getMinBlockZ();
        long seed = level.getSeed();

        for (int x = 0; x < 16; x++) {
            int worldX = worldBaseX + x;
            for (int z = 0; z < 16; z++) {
                int worldZ = worldBaseZ + z;
                int surfaceY = chunk.getHeight(Heightmap.Types.OCEAN_FLOOR, x, z);
                if (surfaceY < level.getMinBuildHeight() || surfaceY >= level.getMaxBuildHeight()) {
                    continue;
                }

                Holder<Biome> biomeHolder = chunk.getNoiseBiome(x >> 2, surfaceY >> 2, z >> 2);
                SurfaceMaterial material = pickSurfaceMaterial(biomeHolder, surfaceY, seaLevel, seed, worldX, worldZ);
                if (material == null) {
                    continue;
                }

                BlockPos surfacePos = new BlockPos(worldX, surfaceY, worldZ);
                changed += replaceSurfaceColumn(chunk, surfacePos, material);
            }
        }

        if (changed > 0) {
            chunk.setUnsaved(true);
        }

        return changed;
    }

    private static SurfaceMaterial pickSurfaceMaterial(Holder<Biome> biomeHolder, int surfaceY, int seaLevel,
                                                       long seed, int worldX, int worldZ) {
        int depthBelowSea = seaLevel - surfaceY;
        boolean isOcean = biomeHolder.is(BiomeTags.IS_OCEAN) || biomeHolder.is(BiomeTags.IS_DEEP_OCEAN);
        boolean isRiver = biomeHolder.is(BiomeTags.IS_RIVER);
        boolean isBeach = biomeHolder.is(BiomeTags.IS_BEACH);

        if (depthBelowSea >= 0) {
            if (depthBelowSea <= BEACH_DEPTH || isBeach || isRiver) {
                return new SurfaceMaterial(Blocks.SAND.defaultBlockState(), OCEAN_FLOOR_DEPTH);
            }
            return pickOceanFloorMaterial(seed, worldX, worldZ);
        }

        if (Math.abs(depthBelowSea) <= BEACH_DEPTH && (isBeach || isOcean || isRiver)) {
            return new SurfaceMaterial(Blocks.SAND.defaultBlockState(), SURFACE_DEPTH);
        }

        return null;
    }

    private static SurfaceMaterial pickOceanFloorMaterial(long seed, int worldX, int worldZ) {
        java.util.Random random = new java.util.Random(seed ^ ((long) worldX * 341873128712L + (long) worldZ * 132897987541L));
        float roll = random.nextFloat();
        if (roll < 0.12f) {
            return new SurfaceMaterial(Blocks.CLAY.defaultBlockState(), OCEAN_FLOOR_DEPTH);
        }
        if (roll < 0.32f) {
            return new SurfaceMaterial(Blocks.GRAVEL.defaultBlockState(), OCEAN_FLOOR_DEPTH);
        }
        return new SurfaceMaterial(Blocks.SAND.defaultBlockState(), OCEAN_FLOOR_DEPTH);
    }

    private static int replaceSurfaceColumn(ChunkAccess chunk, BlockPos surfacePos, SurfaceMaterial material) {
        int changed = 0;
        int minBuildY = chunk.getMinBuildHeight();

        for (int depth = 0; depth < material.depth; depth++) {
            int y = surfacePos.getY() - depth;
            if (y < minBuildY) {
                break;
            }

            int localX = surfacePos.getX() & 15;
            int localY = y & 15;
            int localZ = surfacePos.getZ() & 15;
            int sectionIndex = chunk.getSectionIndex(y);
            if (sectionIndex < 0 || sectionIndex >= chunk.getSectionsCount()) {
                break;
            }

            LevelChunkSection section = chunk.getSection(sectionIndex);
            BlockState state = section.getBlockState(localX, localY, localZ);
            if (!isSurfaceReplaceable(state)) {
                break;
            }
            if (state.is(material.state.getBlock())) {
                continue;
            }

            section.setBlockState(localX, localY, localZ, material.state, false);
            changed++;
        }

        return changed;
    }

    private static boolean isSurfaceReplaceable(BlockState state) {
        return state.is(Blocks.DIRT)
            || state.is(Blocks.COARSE_DIRT)
            || state.is(Blocks.GRASS_BLOCK)
            || state.is(Blocks.STONE)
            || state.is(Blocks.SAND)
            || state.is(Blocks.GRAVEL)
            || state.is(Blocks.CLAY);
    }

    private record SurfaceMaterial(BlockState state, int depth) {
    }
}
