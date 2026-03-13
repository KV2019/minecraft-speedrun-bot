package com.speedrunbot.bot.task;

import com.speedrunbot.bot.BotContext;
import net.minecraft.text.Text;

public final class StatusOverlayTask implements BotTask {
    @Override
    public String name() {
        return "Status Overlay";
    }

    @Override
    public void tick(BotContext context) {
        if (context.world().getTime() % 20 != 0) {
            return;
        }

        String message = String.format(
            "XYZ %.1f %.1f %.1f | HP %.1f | Food %d",
            context.player().getX(),
            context.player().getY(),
            context.player().getZ(),
            context.player().getHealth(),
            context.player().getHungerManager().getFoodLevel()
        );

        context.player().sendMessage(Text.literal(message), true);
    }
}