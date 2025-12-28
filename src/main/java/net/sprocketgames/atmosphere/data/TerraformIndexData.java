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
    private static final String HYDRATION_REVISION_KEY = "hydration_revision";
    private static final int CURRENT_HYDRATION_REVISION = 2;
    private static final String GRASSIFY_ENABLED_KEY = "grassify_enabled";
    private static final String GRASS_PROCESSED_CHUNK_KEYS = "grass_processed_chunk_keys";
    private static final String GRASS_PROCESSED_STATES = "grass_processed_states";
    private static final String GRASS_VEGETATION_ENABLED_KEY = "grass_vegetation_enabled";
    private static final String FLOWER_VEGETATION_ENABLED_KEY = "flower_vegetation_enabled";
    private static final String VEGETATION_PROCESSED_CHUNK_KEYS = "vegetation_processed_chunk_keys";
    private static final String VEGETATION_PROCESSED_STATES = "vegetation_processed_states";

    private long terraformIndex;
    private int waterLevelY = -64;
    private final Long2IntMap processedWaterLevels = new Long2IntOpenHashMap();
    private int hydrationRevision = CURRENT_HYDRATION_REVISION;
    private boolean grassifyEnabled = false;
    private final Long2IntMap processedGrassStates = new Long2IntOpenHashMap();
    private boolean grassVegetationEnabled = false;
    private boolean flowerVegetationEnabled = false;
    private final Long2IntMap processedVegetationStates = new Long2IntOpenHashMap();

    private TerraformIndexData() {
        this(0L);
    }

    private TerraformIndexData(long terraformIndex) {
        this.terraformIndex = terraformIndex;
        this.processedWaterLevels.defaultReturnValue(Integer.MIN_VALUE);
        this.processedGrassStates.defaultReturnValue(-1);
        this.processedVegetationStates.defaultReturnValue(-1);
    }

    public static TerraformIndexData load(CompoundTag tag, HolderLookup.Provider provider) {
        TerraformIndexData data = new TerraformIndexData(tag.getLong(VALUE_KEY));
        if (tag.contains(WATER_LEVEL_KEY)) {
            data.waterLevelY = tag.getInt(WATER_LEVEL_KEY);
        }
        data.hydrationRevision = tag.getInt(HYDRATION_REVISION_KEY);
        long[] processedChunkKeys = tag.getLongArray(PROCESSED_CHUNK_KEYS);
        int[] processedLevels = tag.getIntArray(PROCESSED_WATER_LEVELS);
        int count = Math.min(processedChunkKeys.length, processedLevels.length);
        for (int i = 0; i < count; i++) {
            data.processedWaterLevels.put(processedChunkKeys[i], processedLevels[i]);
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

        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
        tag.putLong(VALUE_KEY, terraformIndex);
        tag.putInt(WATER_LEVEL_KEY, waterLevelY);
        tag.putInt(HYDRATION_REVISION_KEY, hydrationRevision);
        long[] keys = new long[processedWaterLevels.size()];
        int[] values = new int[keys.length];
        int index = 0;
        for (Long2IntMap.Entry entry : processedWaterLevels.long2IntEntrySet()) {
            keys[index] = entry.getLongKey();
            values[index] = entry.getIntValue();
            index++;
        }
        tag.putLongArray(PROCESSED_CHUNK_KEYS, keys);
        tag.putIntArray(PROCESSED_WATER_LEVELS, values);

        tag.putBoolean(GRASSIFY_ENABLED_KEY, grassifyEnabled);
        tag.putBoolean(GRASS_VEGETATION_ENABLED_KEY, grassVegetationEnabled);
        tag.putBoolean(FLOWER_VEGETATION_ENABLED_KEY, flowerVegetationEnabled);
        long[] grassKeys = new long[processedGrassStates.size()];
        int[] grassValues = new int[grassKeys.length];
        int grassIndex = 0;
        for (Long2IntMap.Entry entry : processedGrassStates.long2IntEntrySet()) {
            grassKeys[grassIndex] = entry.getLongKey();
            grassValues[grassIndex] = entry.getIntValue();
            grassIndex++;
        }
        tag.putLongArray(GRASS_PROCESSED_CHUNK_KEYS, grassKeys);
        tag.putIntArray(GRASS_PROCESSED_STATES, grassValues);

        long[] vegKeys = new long[processedVegetationStates.size()];
        int[] vegValues = new int[vegKeys.length];
        int vegIndex = 0;
        for (Long2IntMap.Entry entry : processedVegetationStates.long2IntEntrySet()) {
            vegKeys[vegIndex] = entry.getLongKey();
            vegValues[vegIndex] = entry.getIntValue();
            vegIndex++;
        }
        tag.putLongArray(VEGETATION_PROCESSED_CHUNK_KEYS, vegKeys);
        tag.putIntArray(VEGETATION_PROCESSED_STATES, vegValues);

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
            processedWaterLevels.clear();
            setDirty();
        }
    }

    public boolean isChunkProcessed(long chunkKey, int waterLevel) {
        return processedWaterLevels.get(chunkKey) == waterLevel;
    }

    public int getProcessedWaterLevel(long chunkKey) {
        return processedWaterLevels.get(chunkKey);
    }

    public void markChunkProcessed(long chunkKey, int waterLevel) {
        int previous = processedWaterLevels.put(chunkKey, waterLevel);
        if (previous != waterLevel) {
            setDirty();
        }
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
        int state = processedGrassStates.get(chunkKey);
        return state == (grassifyEnabled ? 1 : 0);
    }

    public void markChunkGrassProcessed(long chunkKey, boolean grassifyEnabled) {
        int newState = grassifyEnabled ? 1 : 0;
        int previous = processedGrassStates.put(chunkKey, newState);
        if (previous != newState) {
            setDirty();
        }
    }

    public boolean isGrassVegetationEnabled() {
        return grassVegetationEnabled;
    }

    public void setGrassVegetationEnabled(boolean grassVegetationEnabled) {
        if (this.grassVegetationEnabled != grassVegetationEnabled) {
            this.grassVegetationEnabled = grassVegetationEnabled;
            processedVegetationStates.clear(); // Clear so chunks get reprocessed for vegetation
            setDirty();
        }
    }

    public boolean isFlowerVegetationEnabled() {
        return flowerVegetationEnabled;
    }

    public void setFlowerVegetationEnabled(boolean flowerVegetationEnabled) {
        if (this.flowerVegetationEnabled != flowerVegetationEnabled) {
            this.flowerVegetationEnabled = flowerVegetationEnabled;
            processedVegetationStates.clear(); // Clear so chunks get reprocessed for vegetation
            setDirty();
        }
    }

    public boolean isChunkVegetationProcessed(long chunkKey, boolean grassVegEnabled, boolean flowerVegEnabled) {
        int state = processedVegetationStates.get(chunkKey);
        int expectedState = (grassVegEnabled ? 1 : 0) | (flowerVegEnabled ? 2 : 0);
        return state == expectedState;
    }

    public void markChunkVegetationProcessed(long chunkKey, boolean grassVegEnabled, boolean flowerVegEnabled) {
        int newState = (grassVegEnabled ? 1 : 0) | (flowerVegEnabled ? 2 : 0);
        int previous = processedVegetationStates.put(chunkKey, newState);
        if (previous != newState) {
            setDirty();
        }
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
