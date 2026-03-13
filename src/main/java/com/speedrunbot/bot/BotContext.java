package com.speedrunbot.bot;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;

public record BotContext(
    MinecraftClient client,
    ClientPlayerEntity player,
    ClientWorld world,
    PlayerActionController actions
) {
    public static BotContext from(MinecraftClient client, PlayerActionController actions) {
        return new BotContext(client, client.player, client.world, actions);
    }

    public boolean isReady() {
        return player != null && world != null;
    }
}
