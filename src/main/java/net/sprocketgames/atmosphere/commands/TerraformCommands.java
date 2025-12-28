package net.sprocketgames.atmosphere.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.sprocketgames.atmosphere.data.TerraformIndexData;
import net.sprocketgames.atmosphere.world.TerraformSurfaceSystem;
import net.sprocketgames.atmosphere.world.TerraformWaterSystem;

public final class TerraformCommands {
    private TerraformCommands() {
    }

    public static void register(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(Commands.literal("terraform")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("get").executes(context -> {
                    ServerLevel overworld = context.getSource().getServer().overworld();
                    TerraformIndexData data = TerraformIndexData.get(overworld);
                    long terraformIndex = data.getTerraformIndex();
                    int waterLevelY = data.getWaterLevelY();
                    boolean grassifyEnabled = data.isGrassifyEnabled();
                    boolean grassVegEnabled = data.isGrassVegetationEnabled();
                    boolean flowerVegEnabled = data.isFlowerVegetationEnabled();
                    context.getSource().sendSuccess(
                            () -> Component.literal("Ti=" + terraformIndex + ", waterLevelY=" + waterLevelY +
                                ", grassify=" + grassifyEnabled + ", grassVeg=" + grassVegEnabled + ", flowers=" + flowerVegEnabled), false);
                    return 1;
                }))
                .then(Commands.literal("setWaterLevel")
                        .then(Commands.argument("y", IntegerArgumentType.integer(-64, 320))
                                .executes(context -> {
                                    int y = IntegerArgumentType.getInteger(context, "y");
                                    ServerLevel overworld = context.getSource().getServer().overworld();
                                    TerraformIndexData data = TerraformIndexData.get(overworld);
                                    data.setWaterLevelY(y);
                                    TerraformWaterSystem.requeueLoaded(overworld);
                                    context.getSource().sendSuccess(
                                            () -> Component.literal("Set waterLevelY to " + y), true);
                                    return 1;
                                })))
                .then(Commands.literal("setGrass")
                        .then(Commands.argument("enabled", BoolArgumentType.bool())
                                .executes(context -> {
                                    boolean enabled = BoolArgumentType.getBool(context, "enabled");
                                    ServerLevel overworld = context.getSource().getServer().overworld();
                                    TerraformIndexData data = TerraformIndexData.get(overworld);
                                    data.setGrassifyEnabled(enabled);
                                    TerraformSurfaceSystem.requeueLoaded(overworld);
                                    context.getSource().sendSuccess(
                                            () -> Component.literal("Set grassify to " + enabled), true);
                                    return 1;
                                })))
                .then(Commands.literal("setGrassVegetation")
                        .then(Commands.argument("enabled", BoolArgumentType.bool())
                                .executes(context -> {
                                    boolean enabled = BoolArgumentType.getBool(context, "enabled");
                                    ServerLevel overworld = context.getSource().getServer().overworld();
                                    TerraformIndexData data = TerraformIndexData.get(overworld);
                                    data.setGrassVegetationEnabled(enabled);
                                    TerraformSurfaceSystem.requeueLoadedVegetation(overworld);
                                    context.getSource().sendSuccess(
                                            () -> Component.literal("Set grass vegetation to " + enabled), true);
                                    return 1;
                                })))
                .then(Commands.literal("setFlowers")
                        .then(Commands.argument("enabled", BoolArgumentType.bool())
                                .executes(context -> {
                                    boolean enabled = BoolArgumentType.getBool(context, "enabled");
                                    ServerLevel overworld = context.getSource().getServer().overworld();
                                    TerraformIndexData data = TerraformIndexData.get(overworld);
                                    data.setFlowerVegetationEnabled(enabled);
                                    TerraformSurfaceSystem.requeueLoadedVegetation(overworld);
                                    context.getSource().sendSuccess(
                                            () -> Component.literal("Set flower vegetation to " + enabled), true);
                                    return 1;
                                }))));
    }
}
