package com.speedrunbot.bot.task;

import com.speedrunbot.bot.BotContext;
import net.minecraft.util.math.MathHelper;

public final class LookAroundTask implements BotTask {
    private float pitchDelta = 0.35F;

    @Override
    public String name() {
        return "Look Around";
    }

    @Override
    public void tick(BotContext context) {
        float yaw = context.player().getYaw() + 1.5F;
        float nextPitch = context.player().getPitch() + pitchDelta;

        if (nextPitch > 20.0F || nextPitch < -20.0F) {
            pitchDelta *= -1.0F;
            nextPitch = context.player().getPitch() + pitchDelta;
        }

        context.player().setYaw(yaw);
        context.player().setPitch(MathHelper.clamp(nextPitch, -20.0F, 20.0F));
    }

    @Override
    public void stop(BotContext context) {
        pitchDelta = 0.35F;
    }
}
