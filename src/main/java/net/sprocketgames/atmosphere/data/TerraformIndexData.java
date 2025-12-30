package net.sprocketgames.atmosphere.data;

import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.sprocketgames.atmosphere.Atmosphere;

/**
 * Stores the global Terraform Index (Ti) in level saved data so it persists with the world.
 */
public class TerraformIndexData extends SavedData {
    private static final String DATA_NAME = Atmosphere.MOD_ID + "_terraform_index";
    private static final String VALUE_KEY = "terraform_index";
    private static final String WATER_LEVEL_KEY = "water_level_y";
    private static final String PROCESSED_CHUNK_KEYS = "processed_chunk_keys";
    private static final String PROCESSED_WATER_LEVELS = "processed_water_levels";
    private static final String PROCESSED_STATE_KEYS = "processed_state_chunk_keys";
    private static final String PROCESSED_STATE_VALUES = "processed_chunk_states";
    private static final String HYDRATION_REVISION_KEY = "hydration_revision";
    private static final int CURRENT_HYDRATION_REVISION = 2;
    private static final String GRASSIFY_ENABLED_KEY = "grassify_enabled";
    private static final String GRASS_PROCESSED_CHUNK_KEYS = "grass_processed_chunk_keys";
    private static final String GRASS_PROCESSED_STATES = "grass_processed_states";
    private static final String GRASS_VEGETATION_ENABLED_KEY = "grass_vegetation_enabled";
    private static final String FLOWER_VEGETATION_ENABLED_KEY = "flower_vegetation_enabled";
    private static final String SAPLING_ENABLED_KEY = "sapling_enabled";
    private static final String AUTO_TERRAFORM_ENABLED_KEY = "auto_terraform_enabled";
    private static final String WATER_LEVEL_OVERRIDE_KEY = "water_level_override";
    private static final String GRASSIFY_OVERRIDE_KEY = "grassify_override";
    private static final String GRASS_VEGETATION_OVERRIDE_KEY = "grass_vegetation_override";
    private static final String FLOWER_VEGETATION_OVERRIDE_KEY = "flower_vegetation_override";
    private static final String SAPLING_OVERRIDE_KEY = "sapling_override";
    private static final String VEGETATION_PROCESSED_CHUNK_KEYS = "vegetation_processed_chunk_keys";
    private static final String VEGETATION_PROCESSED_STATES = "vegetation_processed_states";

    private static final int FEATURE_GRASS = 1;
    private static final int FEATURE_GRASS_VEGETATION = 2;
    private static final int FEATURE_FLOWER_VEGETATION = 4;
    private static final int FEATURE_SAPLINGS = 8;
    private static final int FEATURE_MASK_BITS = 16;

    private long terraformIndex;
    private int waterLevelY = -64;
    private final Long2IntMap processedWaterLevels = new Long2IntOpenHashMap();
    private int hydrationRevision = CURRENT_HYDRATION_REVISION;
    private boolean grassifyEnabled = false;
    private final Long2IntMap processedGrassStates = new Long2IntOpenHashMap();
    private boolean grassVegetationEnabled = false;
    private boolean flowerVegetationEnabled = false;
    private boolean saplingEnabled = false;
    private boolean autoTerraformEnabled = false;
    private boolean waterLevelOverride = false;
    private boolean grassifyOverride = false;
    private boolean grassVegetationOverride = false;
    private boolean flowerVegetationOverride = false;
    private boolean saplingOverride = false;
    private final Long2IntMap processedVegetationStates = new Long2IntOpenHashMap();
    private final it.unimi.dsi.fastutil.longs.Long2LongMap processedStates = new it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap();

    private TerraformIndexData() {
        this(0L);
    }

    private TerraformIndexData(long terraformIndex) {
        this.terraformIndex = terraformIndex;
        this.processedWaterLevels.defaultReturnValue(Integer.MIN_VALUE);
        this.processedGrassStates.defaultReturnValue(-1);
        this.processedVegetationStates.defaultReturnValue(-1);
        this.processedStates.defaultReturnValue(Long.MIN_VALUE);
    }

    public static TerraformIndexData load(CompoundTag tag, HolderLookup.Provider provider) {
        TerraformIndexData data = new TerraformIndexData(tag.getLong(VALUE_KEY));
        if (tag.contains(WATER_LEVEL_KEY)) {
            data.waterLevelY = tag.getInt(WATER_LEVEL_KEY);
        }
        data.hydrationRevision = tag.getInt(HYDRATION_REVISION_KEY);
        if (tag.contains(PROCESSED_STATE_VALUES)) {
            long[] processedChunkKeys = tag.getLongArray(PROCESSED_STATE_KEYS);
            long[] processedStates = tag.getLongArray(PROCESSED_STATE_VALUES);
            int count = Math.min(processedChunkKeys.length, processedStates.length);
            for (int i = 0; i < count; i++) {
                data.processedStates.put(processedChunkKeys[i], processedStates[i]);
            }
        } else {
            long[] processedChunkKeys = tag.getLongArray(PROCESSED_CHUNK_KEYS);
            int[] processedLevels = tag.getIntArray(PROCESSED_WATER_LEVELS);
            int count = Math.min(processedChunkKeys.length, processedLevels.length);
            for (int i = 0; i < count; i++) {
                data.processedWaterLevels.put(processedChunkKeys[i], processedLevels[i]);
            }

            long[] grassChunkKeys = tag.getLongArray(GRASS_PROCESSED_CHUNK_KEYS);
            int[] grassStates = tag.getIntArray(GRASS_PROCESSED_STATES);
            int grassCount = Math.min(grassChunkKeys.length, grassStates.length);
            for (int i = 0; i < grassCount; i++) {
                data.processedGrassStates.put(grassChunkKeys[i], grassStates[i]);
            }

            long[] vegChunkKeys = tag.getLongArray(VEGETATION_PROCESSED_CHUNK_KEYS);
            int[] vegStates = tag.getIntArray(VEGETATION_PROCESSED_STATES);
            int vegCount = Math.min(vegChunkKeys.length, vegStates.length);
            for (int i = 0; i < vegCount; i++) {
                data.processedVegetationStates.put(vegChunkKeys[i], vegStates[i]);
            }

            data.migrateLegacyProcessedStates();
        }
        data.ensureHydrationRevision();

        if (tag.contains(GRASSIFY_ENABLED_KEY)) {
            data.grassifyEnabled = tag.getBoolean(GRASSIFY_ENABLED_KEY);
        }
        if (tag.contains(GRASS_VEGETATION_ENABLED_KEY)) {
            data.grassVegetationEnabled = tag.getBoolean(GRASS_VEGETATION_ENABLED_KEY);
        }
        if (tag.contains(FLOWER_VEGETATION_ENABLED_KEY)) {
            data.flowerVegetationEnabled = tag.getBoolean(FLOWER_VEGETATION_ENABLED_KEY);
        }
        if (tag.contains(SAPLING_ENABLED_KEY)) {
            data.saplingEnabled = tag.getBoolean(SAPLING_ENABLED_KEY);
        }
        if (tag.contains(AUTO_TERRAFORM_ENABLED_KEY)) {
            data.autoTerraformEnabled = tag.getBoolean(AUTO_TERRAFORM_ENABLED_KEY);
        }
        if (tag.contains(WATER_LEVEL_OVERRIDE_KEY)) {
            data.waterLevelOverride = tag.getBoolean(WATER_LEVEL_OVERRIDE_KEY);
        }
        if (tag.contains(GRASSIFY_OVERRIDE_KEY)) {
            data.grassifyOverride = tag.getBoolean(GRASSIFY_OVERRIDE_KEY);
        }
        if (tag.contains(GRASS_VEGETATION_OVERRIDE_KEY)) {
            data.grassVegetationOverride = tag.getBoolean(GRASS_VEGETATION_OVERRIDE_KEY);
        }
        if (tag.contains(FLOWER_VEGETATION_OVERRIDE_KEY)) {
            data.flowerVegetationOverride = tag.getBoolean(FLOWER_VEGETATION_OVERRIDE_KEY);
        }
        if (tag.contains(SAPLING_OVERRIDE_KEY)) {
            data.saplingOverride = tag.getBoolean(SAPLING_OVERRIDE_KEY);
        }

        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
        tag.putLong(VALUE_KEY, terraformIndex);
        tag.putInt(WATER_LEVEL_KEY, waterLevelY);
        tag.putInt(HYDRATION_REVISION_KEY, hydrationRevision);
        tag.putBoolean(GRASSIFY_ENABLED_KEY, grassifyEnabled);
        tag.putBoolean(GRASS_VEGETATION_ENABLED_KEY, grassVegetationEnabled);
        tag.putBoolean(FLOWER_VEGETATION_ENABLED_KEY, flowerVegetationEnabled);
        tag.putBoolean(SAPLING_ENABLED_KEY, saplingEnabled);
        tag.putBoolean(AUTO_TERRAFORM_ENABLED_KEY, autoTerraformEnabled);
        tag.putBoolean(WATER_LEVEL_OVERRIDE_KEY, waterLevelOverride);
        tag.putBoolean(GRASSIFY_OVERRIDE_KEY, grassifyOverride);
        tag.putBoolean(GRASS_VEGETATION_OVERRIDE_KEY, grassVegetationOverride);
        tag.putBoolean(FLOWER_VEGETATION_OVERRIDE_KEY, flowerVegetationOverride);
        tag.putBoolean(SAPLING_OVERRIDE_KEY, saplingOverride);

        long[] keys = new long[processedStates.size()];
        long[] values = new long[keys.length];
        int index = 0;
        for (it.unimi.dsi.fastutil.longs.Long2LongMap.Entry entry : processedStates.long2LongEntrySet()) {
            keys[index] = entry.getLongKey();
            values[index] = entry.getLongValue();
            index++;
        }
        tag.putLongArray(PROCESSED_STATE_KEYS, keys);
        tag.putLongArray(PROCESSED_STATE_VALUES, values);

        return tag;
    }

    public long getTerraformIndex() {
        return terraformIndex;
    }

    public void setTerraformIndex(long terraformIndex) {
        if (this.terraformIndex != terraformIndex) {
            this.terraformIndex = terraformIndex;
            setDirty();
        }
    }

    public int getWaterLevelY() {
        return waterLevelY;
    }

    public void setWaterLevelY(int waterLevelY) {
        if (this.waterLevelY != waterLevelY) {
            this.waterLevelY = waterLevelY;
            setDirty();
        }
    }

    public void ensureHydrationRevision() {
        if (hydrationRevision != CURRENT_HYDRATION_REVISION) {
            hydrationRevision = CURRENT_HYDRATION_REVISION;
            if (processedStates.isEmpty()) {
                processedWaterLevels.clear();
            } else {
                for (it.unimi.dsi.fastutil.longs.Long2LongMap.Entry entry : processedStates.long2LongEntrySet()) {
                    long state = entry.getLongValue();
                    int processedMask = extractProcessedMask(state);
                    int enabledMask = extractEnabledMask(state);
                    long updated = packState(Integer.MIN_VALUE, processedMask, enabledMask);
                    entry.setValue(updated);
                }
            }
            setDirty();
        }
    }

    public boolean isChunkProcessed(long chunkKey, int waterLevel) {
        return isChunkWaterProcessed(chunkKey, waterLevel);
    }

    public int getProcessedWaterLevel(long chunkKey) {
        long state = processedStates.get(chunkKey);
        if (state == Long.MIN_VALUE) {
            return Integer.MIN_VALUE;
        }
        return extractWaterLevel(state);
    }

    public void markChunkProcessed(long chunkKey, int waterLevel) {
        markChunkWaterProcessed(chunkKey, waterLevel);
    }

    public boolean isGrassifyEnabled() {
        return grassifyEnabled;
    }

    public void setGrassifyEnabled(boolean grassifyEnabled) {
        if (this.grassifyEnabled != grassifyEnabled) {
            this.grassifyEnabled = grassifyEnabled;
            setDirty();
        }
    }

    public boolean isChunkGrassProcessed(long chunkKey, boolean grassifyEnabled) {
        long state = processedStates.get(chunkKey);
        if (state == Long.MIN_VALUE) {
            return false;
        }
        int processedMask = extractProcessedMask(state);
        int enabledMask = extractEnabledMask(state);
        if ((processedMask & FEATURE_GRASS) == 0) {
            return false;
        }
        boolean storedEnabled = (enabledMask & FEATURE_GRASS) != 0;
        return storedEnabled == grassifyEnabled;
    }

    public void markChunkGrassProcessed(long chunkKey, boolean grassifyEnabled) {
        long state = processedStates.get(chunkKey);
        int waterLevel = state == Long.MIN_VALUE ? Integer.MIN_VALUE : extractWaterLevel(state);
        int processedMask = state == Long.MIN_VALUE ? 0 : extractProcessedMask(state);
        int enabledMask = state == Long.MIN_VALUE ? 0 : extractEnabledMask(state);
        processedMask |= FEATURE_GRASS;
        if (grassifyEnabled) {
            enabledMask |= FEATURE_GRASS;
        } else {
            enabledMask &= ~FEATURE_GRASS;
        }
        setState(chunkKey, waterLevel, processedMask, enabledMask);
    }

    public boolean isGrassVegetationEnabled() {
        return grassVegetationEnabled;
    }

    public void setGrassVegetationEnabled(boolean grassVegetationEnabled) {
        if (this.grassVegetationEnabled != grassVegetationEnabled) {
            this.grassVegetationEnabled = grassVegetationEnabled;
            setDirty();
        }
    }

    public boolean isFlowerVegetationEnabled() {
        return flowerVegetationEnabled;
    }

    public void setFlowerVegetationEnabled(boolean flowerVegetationEnabled) {
        if (this.flowerVegetationEnabled != flowerVegetationEnabled) {
            this.flowerVegetationEnabled = flowerVegetationEnabled;
            setDirty();
        }
    }

    public boolean isSaplingEnabled() {
        return saplingEnabled;
    }

    public void setSaplingEnabled(boolean saplingEnabled) {
        if (this.saplingEnabled != saplingEnabled) {
            this.saplingEnabled = saplingEnabled;
            setDirty();
        }
    }

    public boolean isAutoTerraformEnabled() {
        return autoTerraformEnabled;
    }

    public void setAutoTerraformEnabled(boolean autoTerraformEnabled) {
        if (this.autoTerraformEnabled != autoTerraformEnabled) {
            this.autoTerraformEnabled = autoTerraformEnabled;
            setDirty();
        }
    }

    public boolean isWaterLevelOverridden() {
        return waterLevelOverride;
    }

    public void setWaterLevelOverride(boolean waterLevelOverride) {
        if (this.waterLevelOverride != waterLevelOverride) {
            this.waterLevelOverride = waterLevelOverride;
            setDirty();
        }
    }

    public boolean isGrassifyOverridden() {
        return grassifyOverride;
    }

    public void setGrassifyOverride(boolean grassifyOverride) {
        if (this.grassifyOverride != grassifyOverride) {
            this.grassifyOverride = grassifyOverride;
            setDirty();
        }
    }

    public boolean isGrassVegetationOverridden() {
        return grassVegetationOverride;
    }

    public void setGrassVegetationOverride(boolean grassVegetationOverride) {
        if (this.grassVegetationOverride != grassVegetationOverride) {
            this.grassVegetationOverride = grassVegetationOverride;
            setDirty();
        }
    }

    public boolean isFlowerVegetationOverridden() {
        return flowerVegetationOverride;
    }

    public void setFlowerVegetationOverride(boolean flowerVegetationOverride) {
        if (this.flowerVegetationOverride != flowerVegetationOverride) {
            this.flowerVegetationOverride = flowerVegetationOverride;
            setDirty();
        }
    }

    public boolean isSaplingOverridden() {
        return saplingOverride;
    }

    public void setSaplingOverride(boolean saplingOverride) {
        if (this.saplingOverride != saplingOverride) {
            this.saplingOverride = saplingOverride;
            setDirty();
        }
    }

    public void clearOverrides() {
        boolean changed = false;
        if (waterLevelOverride) {
            waterLevelOverride = false;
            changed = true;
        }
        if (grassifyOverride) {
            grassifyOverride = false;
            changed = true;
        }
        if (grassVegetationOverride) {
            grassVegetationOverride = false;
            changed = true;
        }
        if (flowerVegetationOverride) {
            flowerVegetationOverride = false;
            changed = true;
        }
        if (saplingOverride) {
            saplingOverride = false;
            changed = true;
        }
        if (changed) {
            setDirty();
        }
    }

    public boolean isChunkVegetationProcessed(long chunkKey, boolean grassVegEnabled, boolean flowerVegEnabled) {
        long state = processedStates.get(chunkKey);
        if (state == Long.MIN_VALUE) {
            return false;
        }
        int processedMask = extractProcessedMask(state);
        int enabledMask = extractEnabledMask(state);
        if (!isFeatureStateProcessed(processedMask, enabledMask, FEATURE_GRASS_VEGETATION, grassVegEnabled)) {
            return false;
        }
        return isFeatureStateProcessed(processedMask, enabledMask, FEATURE_FLOWER_VEGETATION, flowerVegEnabled);
    }

    public void markChunkVegetationProcessed(long chunkKey, boolean grassVegEnabled, boolean flowerVegEnabled) {
        long state = processedStates.get(chunkKey);
        int waterLevel = state == Long.MIN_VALUE ? Integer.MIN_VALUE : extractWaterLevel(state);
        int processedMask = state == Long.MIN_VALUE ? 0 : extractProcessedMask(state);
        int enabledMask = state == Long.MIN_VALUE ? 0 : extractEnabledMask(state);
        processedMask |= FEATURE_GRASS_VEGETATION | FEATURE_FLOWER_VEGETATION;
        if (grassVegEnabled) {
            enabledMask |= FEATURE_GRASS_VEGETATION;
        } else {
            enabledMask &= ~FEATURE_GRASS_VEGETATION;
        }
        if (flowerVegEnabled) {
            enabledMask |= FEATURE_FLOWER_VEGETATION;
        } else {
            enabledMask &= ~FEATURE_FLOWER_VEGETATION;
        }
        setState(chunkKey, waterLevel, processedMask, enabledMask);
    }

    public boolean isChunkWaterProcessed(long chunkKey, int waterLevel) {
        long state = processedStates.get(chunkKey);
        if (state == Long.MIN_VALUE) {
            return false;
        }
        return extractWaterLevel(state) == waterLevel;
    }

    public boolean isChunkSaplingProcessed(long chunkKey, boolean saplingEnabled) {
        long state = processedStates.get(chunkKey);
        if (state == Long.MIN_VALUE) {
            return false;
        }
        int processedMask = extractProcessedMask(state);
        int enabledMask = extractEnabledMask(state);
        return isFeatureStateProcessed(processedMask, enabledMask, FEATURE_SAPLINGS, saplingEnabled);
    }

    public void markChunkSaplingProcessed(long chunkKey, boolean saplingEnabled) {
        long state = processedStates.get(chunkKey);
        int waterLevel = state == Long.MIN_VALUE ? Integer.MIN_VALUE : extractWaterLevel(state);
        int processedMask = state == Long.MIN_VALUE ? 0 : extractProcessedMask(state);
        int enabledMask = state == Long.MIN_VALUE ? 0 : extractEnabledMask(state);
        processedMask |= FEATURE_SAPLINGS;
        if (saplingEnabled) {
            enabledMask |= FEATURE_SAPLINGS;
        } else {
            enabledMask &= ~FEATURE_SAPLINGS;
        }
        setState(chunkKey, waterLevel, processedMask, enabledMask);
    }

    public void markChunkWaterProcessed(long chunkKey, int waterLevel) {
        long state = processedStates.get(chunkKey);
        int processedMask = state == Long.MIN_VALUE ? 0 : extractProcessedMask(state);
        int enabledMask = state == Long.MIN_VALUE ? 0 : extractEnabledMask(state);
        setState(chunkKey, waterLevel, processedMask, enabledMask);
    }

    public void clearChunkWaterProcessed(long chunkKey) {
        long state = processedStates.get(chunkKey);
        if (state == Long.MIN_VALUE) {
            return;
        }
        int processedMask = extractProcessedMask(state);
        int enabledMask = extractEnabledMask(state);
        setState(chunkKey, Integer.MIN_VALUE, processedMask, enabledMask);
    }

    public void clearChunkState(long chunkKey) {
        if (processedStates.remove(chunkKey) != Long.MIN_VALUE) {
            setDirty();
        }
    }

    private void migrateLegacyProcessedStates() {
        if (processedStates.isEmpty()) {
            for (Long2IntMap.Entry entry : processedWaterLevels.long2IntEntrySet()) {
                processedStates.put(entry.getLongKey(), packState(entry.getIntValue(), 0, 0));
            }
            for (Long2IntMap.Entry entry : processedGrassStates.long2IntEntrySet()) {
                long state = processedStates.get(entry.getLongKey());
                int waterLevel = state == Long.MIN_VALUE ? Integer.MIN_VALUE : extractWaterLevel(state);
                int processedMask = state == Long.MIN_VALUE ? 0 : extractProcessedMask(state);
                int enabledMask = state == Long.MIN_VALUE ? 0 : extractEnabledMask(state);
                processedMask |= FEATURE_GRASS;
                if (entry.getIntValue() == 1) {
                    enabledMask |= FEATURE_GRASS;
                } else {
                    enabledMask &= ~FEATURE_GRASS;
                }
                processedStates.put(entry.getLongKey(), packState(waterLevel, processedMask, enabledMask));
            }
            for (Long2IntMap.Entry entry : processedVegetationStates.long2IntEntrySet()) {
                long state = processedStates.get(entry.getLongKey());
                int waterLevel = state == Long.MIN_VALUE ? Integer.MIN_VALUE : extractWaterLevel(state);
                int processedMask = state == Long.MIN_VALUE ? 0 : extractProcessedMask(state);
                int enabledMask = state == Long.MIN_VALUE ? 0 : extractEnabledMask(state);
                processedMask |= FEATURE_GRASS_VEGETATION | FEATURE_FLOWER_VEGETATION;
                if ((entry.getIntValue() & 1) != 0) {
                    enabledMask |= FEATURE_GRASS_VEGETATION;
                } else {
                    enabledMask &= ~FEATURE_GRASS_VEGETATION;
                }
                if ((entry.getIntValue() & 2) != 0) {
                    enabledMask |= FEATURE_FLOWER_VEGETATION;
                } else {
                    enabledMask &= ~FEATURE_FLOWER_VEGETATION;
                }
                processedStates.put(entry.getLongKey(), packState(waterLevel, processedMask, enabledMask));
            }
        }
        processedWaterLevels.clear();
        processedGrassStates.clear();
        processedVegetationStates.clear();
    }

    private long packState(int waterLevel, int processedMask, int enabledMask) {
        return ((long) waterLevel << 32) | ((long) processedMask << FEATURE_MASK_BITS) | (enabledMask & 0xFFFFL);
    }

    private void setState(long chunkKey, int waterLevel, int processedMask, int enabledMask) {
        long newState = packState(waterLevel, processedMask, enabledMask);
        long previous = processedStates.put(chunkKey, newState);
        if (previous != newState) {
            setDirty();
        }
    }

    private int extractWaterLevel(long state) {
        return (int) (state >> 32);
    }

    private int extractProcessedMask(long state) {
        return (int) ((state >> FEATURE_MASK_BITS) & 0xFFFF);
    }

    private int extractEnabledMask(long state) {
        return (int) (state & 0xFFFF);
    }

    private boolean isFeatureStateProcessed(int processedMask, int enabledMask, int feature, boolean enabled) {
        if ((processedMask & feature) == 0) {
            return false;
        }
        boolean storedEnabled = (enabledMask & feature) != 0;
        return storedEnabled == enabled;
    }

    /**
     * Ensures the data class is touched during common setup so the ID is reserved before first use.
     */
    public static void bootstrap() {
        // No-op. Exists so common setup can reference this class and make sure it is loaded.
    }

    public static TerraformIndexData get(ServerLevel level) {
        var overworld = level.getServer().overworld();
        var factory = new SavedData.Factory<>(TerraformIndexData::new, TerraformIndexData::load);
        return overworld.getDataStorage().computeIfAbsent(factory, DATA_NAME);
    }
}
