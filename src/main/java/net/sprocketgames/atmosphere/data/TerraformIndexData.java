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

    private long terraformIndex;
    private int waterLevelY = -64;
    private final Long2IntMap processedWaterLevels = new Long2IntOpenHashMap();
    private int hydrationRevision = CURRENT_HYDRATION_REVISION;

    private TerraformIndexData() {
        this(0L);
    }

    private TerraformIndexData(long terraformIndex) {
        this.terraformIndex = terraformIndex;
        this.processedWaterLevels.defaultReturnValue(Integer.MIN_VALUE);
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
            this.processedWaterLevels.clear();
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

    public void markChunkProcessed(long chunkKey, int waterLevel) {
        int previous = processedWaterLevels.put(chunkKey, waterLevel);
        if (previous != waterLevel) {
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
