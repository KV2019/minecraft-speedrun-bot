package com.speedrunbot.bot;

import com.speedrunbot.bot.task.BotTask;
import com.speedrunbot.bot.task.AutoMineNearestLogTask;
import com.speedrunbot.bot.task.CraftBasicWoodTask;
import com.speedrunbot.bot.task.CraftWoodenPickaxeTask;
import com.speedrunbot.bot.task.MineDownToStoneTask;
import com.speedrunbot.bot.task.CraftStoneToolsTask;
import com.speedrunbot.bot.task.HuntAnimalsTask;
import java.util.List;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;

public final class BotController {
    private final PlayerActionController actionController = new PlayerActionController();
    @SuppressWarnings("null")
    private final List<BotTask> tasks = List.of(
        new AutoMineNearestLogTask(),
        new CraftBasicWoodTask(),
        new CraftWoodenPickaxeTask(),
        new MineDownToStoneTask(),
        new CraftStoneToolsTask(),
        new HuntAnimalsTask()
    );

    private boolean running;
    private boolean taskStarted;
    private boolean manualTaskSelection;
    private int activeTaskIndex;
    private int taskRunTicks;

    private static final int MIN_TASK_TICKS = 20;

    public void toggle(MinecraftClient client) {
        running = !running;

        if (!running) {
            stopActiveTask(client);
            manualTaskSelection = false;
            sendStatus(client, "Bot stopped");
            return;
        }

        taskStarted = false;
        taskRunTicks = 0;
        sendStatus(client, "Bot enabled: " + activeTask().name());
    }

    public void advanceTask(MinecraftClient client) {
        stopActiveTask(client);
        running = false;
        manualTaskSelection = true;
        activeTaskIndex = (activeTaskIndex + 1) % tasks.size();
        taskStarted = false;
        taskRunTicks = 0;
        sendStatus(client, "Selected task: " + activeTask().name() + " (press toggle to start)");
    }

    public void onEndTick(MinecraftClient client) {
        if (!running || client.isPaused()) {
            actionController.releaseAll(client);
            return;
        }

        BotContext context = BotContext.from(client, actionController);
        if (!context.isReady()) {
            actionController.releaseAll(client);
            return;
        }

        actionController.reset();

        if (!taskStarted) {
            activeTask().start(context);
            taskStarted = true;
            taskRunTicks = 0;
        }

        activeTask().tick(context);
        actionController.apply(client);
        taskRunTicks++;

        if (taskRunTicks >= MIN_TASK_TICKS && activeTask().isFinished(context)) {
            activeTask().stop(context);
            actionController.releaseAll(client);
            taskStarted = false;
            taskRunTicks = 0;

            if (manualTaskSelection) {
                running = false;
                sendStatus(client, "Task finished: " + activeTask().name());
                return;
            }

            if (activeTask() instanceof MineDownToStoneTask && !hasStoneCraftPrerequisites(context)) {
                sendStatus(client, "Stone mining ended early; retrying until crafting prerequisites are ready");
                return;
            }

            activeTaskIndex = (activeTaskIndex + 1) % tasks.size();
            sendStatus(client, "Advanced to task: " + activeTask().name());
        }
    }

    private BotTask activeTask() {
        return tasks.get(activeTaskIndex);
    }

    private void stopActiveTask(MinecraftClient client) {
        if (!taskStarted) {
            return;
        }

        BotContext context = BotContext.from(client, actionController);
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

    private static boolean hasStoneCraftPrerequisites(BotContext context) {
        return countItem(context, Items.COBBLESTONE) + countItem(context, Items.COBBLED_DEEPSLATE) >= 3
            && countItem(context, Items.STICK) >= 2;
    }

    private static int countItem(BotContext context, Item item) {
        int total = 0;
        for (int i = 0; i < context.player().getInventory().size(); i++) {
            ItemStack stack = context.player().getInventory().getStack(i);
            if (stack.isOf(item)) {
                total += stack.getCount();
            }
        }
        return total;
    }
}
