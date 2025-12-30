package net.sprocketgames.atmosphere.world;

import java.util.HashMap;
import java.util.Map;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.sprocketgames.atmosphere.data.TerraformIndexData;
import net.sprocketgames.atmosphere.network.AtmosphereNetwork;

public final class TerraformIndexController {
    private static final int TICKS_PER_SECOND = 20;
    private static final int WATER_BASE_LEVEL = -64;
    private static final int WATER_MAX_LEVEL = 61;
    private static final long GRASSIFY_TI = 150;
    private static final long GRASS_VEGETATION_TI = 170;
    private static final long FLOWER_TI = 190;
    private static final long SAPLING_TI = 210;
    private static final Map<ResourceKey<Level>, Integer> AUTO_TICK_COUNTERS = new HashMap<>();

    private TerraformIndexController() {
    }

    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel serverLevel)) {
            return;
        }

        if (serverLevel.dimension() != Level.OVERWORLD) {
            return;
        }

        tickAutoTerraformIndex(serverLevel);
    }

    public static void setTerraformIndex(ServerLevel level, long terraformIndex, boolean forceApply) {
        TerraformIndexData data = TerraformIndexData.get(level);
        data.setTerraformIndex(terraformIndex);
        if (forceApply) {
            data.clearOverrides();
        }
        boolean changed = applyTerraformIndex(level, data, forceApply);
        if (changed) {
            TerraformSystem.requeueLoaded(level);
        }
        AtmosphereNetwork.sendTerraformIndex(level, terraformIndex);
    }

    public static boolean applyTerraformIndex(ServerLevel level, TerraformIndexData data, boolean ignoreOverrides) {
        long terraformIndex = data.getTerraformIndex();
        int targetWaterLevel = computeWaterLevel(terraformIndex);
        boolean targetGrassify = terraformIndex >= GRASSIFY_TI;
        boolean targetGrassVegetation = terraformIndex >= GRASS_VEGETATION_TI;
        boolean targetFlowerVegetation = terraformIndex >= FLOWER_TI;
        boolean targetSaplings = terraformIndex >= SAPLING_TI;
        boolean changed = false;

        if (ignoreOverrides || !data.isWaterLevelOverridden()) {
            if (data.getWaterLevelY() != targetWaterLevel) {
                data.setWaterLevelY(targetWaterLevel);
                changed = true;
            }
        }

        if (ignoreOverrides || !data.isGrassifyOverridden()) {
            if (data.isGrassifyEnabled() != targetGrassify) {
                data.setGrassifyEnabled(targetGrassify);
                changed = true;
            }
        }

        if (ignoreOverrides || !data.isGrassVegetationOverridden()) {
            if (data.isGrassVegetationEnabled() != targetGrassVegetation) {
                data.setGrassVegetationEnabled(targetGrassVegetation);
                changed = true;
            }
        }

        if (ignoreOverrides || !data.isFlowerVegetationOverridden()) {
            if (data.isFlowerVegetationEnabled() != targetFlowerVegetation) {
                data.setFlowerVegetationEnabled(targetFlowerVegetation);
                changed = true;
            }
        }

        if (ignoreOverrides || !data.isSaplingOverridden()) {
            if (data.isSaplingEnabled() != targetSaplings) {
                data.setSaplingEnabled(targetSaplings);
                changed = true;
            }
        }

        return changed;
    }

    private static int computeWaterLevel(long terraformIndex) {
        if (terraformIndex <= 0) {
            return WATER_BASE_LEVEL;
        }
        int target = WATER_BASE_LEVEL + (int) terraformIndex;
        return Math.min(target, WATER_MAX_LEVEL);
    }

    private static void tickAutoTerraformIndex(ServerLevel level) {
        TerraformIndexData data = TerraformIndexData.get(level);
        ResourceKey<Level> key = level.dimension();
        if (!data.isAutoTerraformEnabled()) {
            AUTO_TICK_COUNTERS.remove(key);
            return;
        }

        int ticks = AUTO_TICK_COUNTERS.getOrDefault(key, 0) + 1;
        if (ticks >= TICKS_PER_SECOND) {
            ticks = 0;
            long nextTerraformIndex = data.getTerraformIndex() + 1;
            setTerraformIndex(level, nextTerraformIndex, true);
        }
        AUTO_TICK_COUNTERS.put(key, ticks);
    }
}
