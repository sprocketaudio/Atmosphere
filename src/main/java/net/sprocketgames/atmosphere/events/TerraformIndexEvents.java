package net.sprocketgames.atmosphere.events;

import net.minecraft.core.BlockPos;
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
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.sprocketgames.atmosphere.Atmosphere;
import net.sprocketgames.atmosphere.data.TerraformIndexData;
import net.sprocketgames.atmosphere.network.AtmosphereNetwork;

@EventBusSubscriber(modid = Atmosphere.MOD_ID, bus = EventBusSubscriber.Bus.FORGE)
public class TerraformIndexEvents {
    private static final String PROCESSED_CHUNK_KEY = Atmosphere.MOD_ID + ":water_removed";
    private static final long WATER_PLACE_THRESHOLD = 1_000_000L;

    @SubscribeEvent
    public static void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }

        // Sync the latest Terraform Index to the player as soon as they join the server.
        ServerLevel level = player.serverLevel();
        long terraformIndex = TerraformIndexData.get(level).getTerraformIndex();
        AtmosphereNetwork.sendTerraformIndex(player, terraformIndex);
    }

    @SubscribeEvent
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

        if (levelChunk.getPersistentData().getBoolean(PROCESSED_CHUNK_KEY)) {
            return;
        }

        // Strip naturally generated water when an Overworld chunk is first loaded.
        clearWaterFromChunk(serverLevel, levelChunk);
        levelChunk.getPersistentData().putBoolean(PROCESSED_CHUNK_KEY, true);
        levelChunk.setUnsaved(true);
    }

    @SubscribeEvent
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

        for (LevelChunkSection section : chunk.getSections()) {
            if (section == null || section.hasOnlyAir()) {
                continue;
            }

            PalettedContainer<BlockState> states = section.getStates();
            int sectionMinY = section.bottomBlockY();

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
