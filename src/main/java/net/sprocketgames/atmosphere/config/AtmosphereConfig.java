package net.sprocketgames.atmosphere.config;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class AtmosphereConfig {
    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.BooleanValue DEBUG_LOGGING;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

        builder.push("logging");
        DEBUG_LOGGING = builder
            .comment("Enable extra debug logging for terraform processing.")
            .define("debugLogging", false);
        builder.pop();

        SPEC = builder.build();
    }

    private AtmosphereConfig() {
    }
}
