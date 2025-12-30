package net.sprocketgames.atmosphere.config;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class AtmosphereConfig {
    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.BooleanValue DEBUG_LOGGING;
    public static final ModConfigSpec.BooleanValue NO_WATER_WORLDGEN;
    public static final ModConfigSpec.IntValue TERRAFORM_CHUNKS_PER_TICK;
    public static final ModConfigSpec.IntValue TERRAFORM_PRIORITY_CHUNKS_PER_TICK;
    public static final ModConfigSpec.IntValue TERRAFORM_WATER_BUDGET;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

        builder.push("logging");
        DEBUG_LOGGING = builder
            .comment("Enable extra debug logging for terraform processing.")
            .define("debugLogging", false);
        builder.pop();

        builder.push("worldgen");
        NO_WATER_WORLDGEN = builder
            .comment("Enable the custom no-water worldgen surface pass (virtual sea level, beaches, ocean floor).")
            .define("noWaterWorldgen", true);
        builder.pop();

        builder.push("terraform");
        TERRAFORM_CHUNKS_PER_TICK = builder
            .comment("Max chunks processed per tick by the terraform queue.")
            .defineInRange("terraformChunksPerTick", 4, 1, 128);
        TERRAFORM_PRIORITY_CHUNKS_PER_TICK = builder
            .comment("Max priority chunks processed per tick by the terraform queue.")
            .defineInRange("terraformPriorityChunksPerTick", 8, 1, 256);
        TERRAFORM_WATER_BUDGET = builder
            .comment("Max flood-fill nodes processed per chunk per tick when applying terraform water.")
            .defineInRange("terraformWaterBudget", 2000, 100, 200000);
        builder.pop();

        SPEC = builder.build();
    }

    private AtmosphereConfig() {
    }
}
