package com.speedrunbot.bot;

import com.speedrunbot.bot.navigation.Navigator;
import com.speedrunbot.bot.task.BotTask;
import com.speedrunbot.bot.task.AutoMineNearestLogTask;
import java.util.List;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

public final class BotController {
    private final PlayerActionController actionController = new PlayerActionController();
    private final Navigator navigator = new Navigator();
    private final List<BotTask> tasks = List.of(
        new AutoMineNearestLogTask()
    );

    private boolean running;
    private boolean taskStarted;
    private int activeTaskIndex;

    public void toggle(MinecraftClient client) {
        running = !running;

        if (!running) {
            stopActiveTask(client);
            sendStatus(client, "Bot stopped");
            return;
        }

        taskStarted = false;
        sendStatus(client, "Bot enabled: " + activeTask().name());
    }

    public void advanceTask(MinecraftClient client) {
        // Single-task mode: pressing N restarts the current task.
        stopActiveTask(client);
        taskStarted = false;
        sendStatus(client, "Restarted task: " + activeTask().name());
    }

    public void onEndTick(MinecraftClient client) {
        if (!running || client.isPaused()) {
            actionController.releaseAll(client);
            return;
        }

        BotContext context = BotContext.from(client, actionController, navigator);
        if (!context.isReady()) {
            actionController.releaseAll(client);
            return;
        }

        actionController.reset();

        if (!taskStarted) {
            activeTask().start(context);
            taskStarted = true;
        }

        activeTask().tick(context);
        actionController.apply(client);

        if (activeTask().isFinished(context)) {
            activeTask().stop(context);
            actionController.releaseAll(client);
            taskStarted = false;
            sendStatus(client, "Task complete: " + activeTask().name());
        }
    }

    private BotTask activeTask() {
        return tasks.get(activeTaskIndex);
    }

    private void stopActiveTask(MinecraftClient client) {
        if (!taskStarted) {
            return;
        }

        BotContext context = BotContext.from(client, actionController, navigator);
        if (!context.isReady()) {
            taskStarted = false;
            return;
        }

        activeTask().stop(context);
        actionController.releaseAll(client);
        taskStarted = false;
    }

    private void sendStatus(MinecraftClient client, String message) {
        if (client.player == null) {
            return;
        }

        client.player.sendMessage(Text.literal("[SpeedrunBot] " + message), true);
    }
}
