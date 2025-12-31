package net.sprocketgames.atmosphere.worldgen;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.Holder;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.StructureManager;

public class NoWaterChunkGenerator extends NoiseBasedChunkGenerator {
    public static final MapCodec<NoWaterChunkGenerator> CODEC = RecordCodecBuilder.mapCodec(
        instance -> instance.group(
                BiomeSource.CODEC.fieldOf("biome_source").forGetter(NoWaterChunkGenerator::biomeSourceForCodec),
                NoiseGeneratorSettings.CODEC.fieldOf("settings").forGetter(NoWaterChunkGenerator::originalSettings)
            )
            .apply(instance, instance.stable(NoWaterChunkGenerator::new))
    );

    private final Holder<NoiseGeneratorSettings> originalSettings;

    public NoWaterChunkGenerator(BiomeSource biomeSource, Holder<NoiseGeneratorSettings> settings) {
        super(biomeSource, NoWaterWorldgen.createNoWaterSettings(settings));
        this.originalSettings = settings;
    }

    private BiomeSource biomeSourceForCodec() {
        return this.biomeSource;
    }

    private Holder<NoiseGeneratorSettings> originalSettings() {
        return this.originalSettings;
    }

    @Override
    protected MapCodec<? extends ChunkGenerator> codec() {
        return CODEC;
    }

    @Override
    public void buildSurface(WorldGenRegion level, StructureManager structureManager, RandomState random, ChunkAccess chunk) {
        super.buildSurface(level, structureManager, random, chunk);
        NoWaterWorldgen.applySurfacePass(level, chunk, this.generatorSettings().value().seaLevel());
    }

    @Override
    public void applyBiomeDecoration(WorldGenLevel level, ChunkAccess chunk, StructureManager structureManager) {
        super.applyBiomeDecoration(level, chunk, structureManager);
        NoWaterWorldgen.stripWaterFeatures(level, chunk);
    }
}
