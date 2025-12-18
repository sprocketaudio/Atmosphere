package net.sprocketgames.atmosphere.client;

import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.sprocketgames.atmosphere.Atmosphere;

@Mod(value = Atmosphere.MOD_ID, dist = Dist.CLIENT)
public class AtmosphereClient {
    public AtmosphereClient() {
        NeoForge.EVENT_BUS.addListener(this::renderTerraformIndex);
    }

    @SubscribeEvent
    // Render the Terraform Index in the HUD using the latest value received from the server.
    public void renderTerraformIndex(RenderGuiEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null) {
            return;
        }

        var guiGraphics = event.getGuiGraphics();
        String text = "Ti: " + ClientTerraformIndex.getTerraformIndex();
        int textWidth = minecraft.font.width(text);
        int x = minecraft.getWindow().getGuiScaledWidth() - textWidth - 4;
        int y = 4;

        guiGraphics.drawString(minecraft.font, text, x, y, 0xFFFFFF, true);
    }
}
