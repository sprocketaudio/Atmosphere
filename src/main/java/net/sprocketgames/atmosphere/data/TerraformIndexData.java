package net.sprocketgames.atmosphere.data;

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

    private long terraformIndex;

    private TerraformIndexData() {
        this(0L);
    }

    private TerraformIndexData(long terraformIndex) {
        this.terraformIndex = terraformIndex;
    }

    public static TerraformIndexData load(CompoundTag tag) {
        return new TerraformIndexData(tag.getLong(VALUE_KEY));
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        tag.putLong(VALUE_KEY, terraformIndex);
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
