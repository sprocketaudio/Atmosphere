package net.sprocketgames.atmosphere.config;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class AtmosphereConfig {
    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.BooleanValue DEBUG_LOGGING;
    public static final ModConfigSpec.BooleanValue NO_WATER_WORLDGEN;

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

        SPEC = builder.build();
    }

    private AtmosphereConfig() {
    }
}
