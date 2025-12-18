package net.sprocketgames.atmosphere.data;

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

    private long terraformIndex;
    private int waterLevelY = -64;

    private TerraformIndexData() {
        this(0L);
    }

    private TerraformIndexData(long terraformIndex) {
        this.terraformIndex = terraformIndex;
    }

    public static TerraformIndexData load(CompoundTag tag, HolderLookup.Provider provider) {
        TerraformIndexData data = new TerraformIndexData(tag.getLong(VALUE_KEY));
        if (tag.contains(WATER_LEVEL_KEY)) {
            data.waterLevelY = tag.getInt(WATER_LEVEL_KEY);
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
        tag.putLong(VALUE_KEY, terraformIndex);
        tag.putInt(WATER_LEVEL_KEY, waterLevelY);
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

    public boolean isChunkProcessed(long chunkKey) {
        return false;
    }

    public void markChunkProcessed(long chunkKey) {
        // No-op placeholder to preserve API shape while chunk bookkeeping is unused.
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
