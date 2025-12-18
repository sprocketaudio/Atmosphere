package net.sprocketgames.atmosphere.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.sprocketgames.atmosphere.data.TerraformIndexData;
import net.sprocketgames.atmosphere.world.TerraformWaterSystem;

public final class TerraformCommands {
    private TerraformCommands() {
    }

    public static void register(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(Commands.literal("terraform")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("get").executes(context -> {
                    ServerLevel level = context.getSource().getLevel();
                    TerraformIndexData data = TerraformIndexData.get(level);
                    long terraformIndex = data.getTerraformIndex();
                    int waterLevelY = data.getWaterLevelY();
                    context.getSource().sendSuccess(
                            () -> Component.literal("Ti=" + terraformIndex + ", waterLevelY=" + waterLevelY), false);
                    return 1;
                }))
                .then(Commands.literal("setWaterLevel")
                        .then(Commands.argument("y", IntegerArgumentType.integer(-64, 320))
                                .executes(context -> {
                                    int y = IntegerArgumentType.getInteger(context, "y");
                                    ServerLevel level = context.getSource().getLevel();
                                    TerraformIndexData data = TerraformIndexData.get(level);
                                    data.setWaterLevelY(y);
                                    TerraformWaterSystem.requeueLoaded(level);
                                    context.getSource().sendSuccess(
                                            () -> Component.literal("Set waterLevelY to " + y), true);
                                    return 1;
                                }))));
    }
}
