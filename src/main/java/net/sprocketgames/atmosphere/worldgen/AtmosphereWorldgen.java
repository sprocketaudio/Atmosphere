package net.sprocketgames.atmosphere.worldgen;

import com.mojang.serialization.MapCodec;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.sprocketgames.atmosphere.Atmosphere;

public final class AtmosphereWorldgen {
    public static final DeferredRegister<MapCodec<? extends ChunkGenerator>> CHUNK_GENERATORS =
        DeferredRegister.create(Registries.CHUNK_GENERATOR, Atmosphere.MOD_ID);

    public static final DeferredHolder<MapCodec<? extends ChunkGenerator>, MapCodec<NoWaterChunkGenerator>> NO_WATER =
        CHUNK_GENERATORS.register("no_water", () -> NoWaterChunkGenerator.CODEC);

    private AtmosphereWorldgen() {
    }

    public static void register(IEventBus bus) {
        CHUNK_GENERATORS.register(bus);
    }
}
