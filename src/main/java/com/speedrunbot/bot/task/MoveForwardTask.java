package com.speedrunbot.bot.task;

import com.speedrunbot.bot.BotContext;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;

public final class MoveForwardTask implements BotTask {
    private static final int TEST_DURATION_TICKS = 200;

    private int ticks;

    @Override
    public String name() {
        return "Move Forward";
    }

    @Override
    public void start(BotContext context) {
        ticks = 0;
        context.player().sendMessage(Text.literal("[SpeedrunBot] Movement task started"), true);
    }

    @Override
    public void tick(BotContext context) {
        ticks++;

        context.actions().setForward(true);
        context.actions().setSprint(true);

        if (context.player().isOnGround() && ticks % 20 == 0) {
            context.actions().setJump(true);
        }

        float yaw = context.player().getYaw() + 0.25F;
        context.player().setYaw(yaw);
        context.player().setPitch(MathHelper.clamp(context.player().getPitch(), -15.0F, 15.0F));
    }

    @Override
    public boolean isFinished(BotContext context) {
        return ticks >= TEST_DURATION_TICKS;
    }

    @Override
    public void stop(BotContext context) {
        ticks = 0;
    }
}