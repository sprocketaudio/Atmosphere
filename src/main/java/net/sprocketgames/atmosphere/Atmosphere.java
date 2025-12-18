package net.sprocketgames.atmosphere;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.sprocketgames.atmosphere.data.TerraformIndexData;
import net.sprocketgames.atmosphere.network.AtmosphereNetwork;

// The value here should match an entry in the META-INF/neoforge.mods.toml file
@Mod(Atmosphere.MOD_ID)
public class Atmosphere {
    public static final String MOD_ID = "atmosphere";
    public static final Logger LOGGER = LogUtils.getLogger();

    public Atmosphere(IEventBus modEventBus) {
        modEventBus.addListener(this::onCommonSetup);
    }

    private void onCommonSetup(FMLCommonSetupEvent event) {
        // Register networking and ensure the Terraform Index saved data is ready to use.
        event.enqueueWork(() -> {
            AtmosphereNetwork.register();
            TerraformIndexData.bootstrap();
        });
    }

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
    }
}
