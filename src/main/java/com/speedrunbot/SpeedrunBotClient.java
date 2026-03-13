package com.speedrunbot;

import com.speedrunbot.bot.BotController;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;

public final class SpeedrunBotClient implements ClientModInitializer {
    private static final BotController BOT_CONTROLLER = new BotController();

    private KeyBinding toggleBotKey;
    private KeyBinding nextTaskKey;

    @Override
    public void onInitializeClient() {
        toggleBotKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.speedrunbot.toggle",
            InputUtil.Type.KEYSYM,
            GLFW.GLFW_KEY_B,
            "category.speedrunbot.controls"
        ));

        nextTaskKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.speedrunbot.next_task",
            InputUtil.Type.KEYSYM,
            GLFW.GLFW_KEY_N,
            "category.speedrunbot.controls"
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (toggleBotKey.wasPressed()) {
                BOT_CONTROLLER.toggle(client);
            }

            while (nextTaskKey.wasPressed()) {
                BOT_CONTROLLER.advanceTask(client);
            }

            BOT_CONTROLLER.onEndTick(client);
        });
    }
}
