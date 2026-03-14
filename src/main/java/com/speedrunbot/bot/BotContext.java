package com.speedrunbot.bot;

import com.speedrunbot.bot.navigation.Navigator;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;

public record BotContext(
    MinecraftClient client,
    ClientPlayerEntity player,
    ClientWorld world,
    PlayerActionController actions,
    Navigator navigator
) {
    public static BotContext from(MinecraftClient client, PlayerActionController actions, Navigator navigator) {
        return new BotContext(client, client.player, client.world, actions, navigator);
    }

    public boolean isReady() {
        return player != null && world != null;
    }
}
