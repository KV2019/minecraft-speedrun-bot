package com.speedrunbot.bot.task;

import com.speedrunbot.bot.BotContext;

public interface BotTask {
    String name();

    default void start(BotContext context) {
    }

    void tick(BotContext context);

    default boolean isFinished(BotContext context) {
        return false;
    }

    default void stop(BotContext context) {
    }
}
