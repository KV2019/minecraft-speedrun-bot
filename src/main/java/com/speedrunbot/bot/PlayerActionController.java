package com.speedrunbot.bot;

import net.minecraft.client.MinecraftClient;

public final class PlayerActionController {
    private boolean forward;
    private boolean back;
    private boolean left;
    private boolean right;
    private boolean jump;
    private boolean sprint;
    private boolean sneak;
    private boolean attack;
    private boolean use;

    public void reset() {
        forward = false;
        back = false;
        left = false;
        right = false;
        jump = false;
        sprint = false;
        sneak = false;
        attack = false;
        use = false;
    }

    public void setForward(boolean value) {
        forward = value;
    }

    public void setBack(boolean value) {
        back = value;
    }

    public void setLeft(boolean value) {
        left = value;
    }

    public void setRight(boolean value) {
        right = value;
    }

    public void setJump(boolean value) {
        jump = value;
    }

    public void setSprint(boolean value) {
        sprint = value;
    }

    public void setSneak(boolean value) {
        sneak = value;
    }

    public void setAttack(boolean value) {
        attack = value;
    }

    public void setUse(boolean value) {
        use = value;
    }

    public void apply(MinecraftClient client) {
        client.options.forwardKey.setPressed(forward);
        client.options.backKey.setPressed(back);
        client.options.leftKey.setPressed(left);
        client.options.rightKey.setPressed(right);
        client.options.jumpKey.setPressed(jump);
        client.options.sprintKey.setPressed(sprint);
        client.options.sneakKey.setPressed(sneak);
        client.options.attackKey.setPressed(attack);
        client.options.useKey.setPressed(use);
    }

    public void releaseAll(MinecraftClient client) {
        reset();
        apply(client);
    }
}