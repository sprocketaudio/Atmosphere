package net.sprocketgames.atmosphere.world;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

/**
 * Applies surface replacements during world generation (e.g., grass to dirt).
 */
public final class TerraformSurfaceSystem {
    private TerraformSurfaceSystem() {
    }

    public static void replaceGrassWithDirt(LevelChunk chunk, ServerLevel level) {
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
    }
}
