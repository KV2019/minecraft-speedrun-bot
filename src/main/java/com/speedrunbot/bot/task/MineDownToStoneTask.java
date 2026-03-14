package com.speedrunbot.bot.task;

import com.speedrunbot.bot.BotContext;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;

public final class MineDownToStoneTask implements BotTask {

    private enum State {
        EQUIP_PICKAXE,
        DIG_DOWN,
        DONE
    }

    private static final int COBBLESTONE_TARGET = 16;
    private static final int MAX_DIG_DEPTH = 60;

    private State state;
    private int ticks;
    private int actionCooldown;
    private double startY;
    private int lastCobblestoneCount;

    @Override
    public String name() {
        return "Mine Down to Stone";
    }

    @Override
    public void start(BotContext context) {
        state = State.EQUIP_PICKAXE;
        ticks = 0;
        actionCooldown = 0;
        startY = context.player().getY();
        lastCobblestoneCount = countCobblestone(context.player());
        context.player().sendMessage(Text.literal("[SpeedrunBot] Mining down to stone"), true);
    }

    @Override
    public void tick(BotContext context) {
        ticks++;

        if (actionCooldown > 0) {
            actionCooldown--;
            return;
        }

        switch (state) {
            case EQUIP_PICKAXE -> tickEquipPickaxe(context);
            case DIG_DOWN      -> tickDigDown(context);
            case DONE          -> {}
        }
    }

    @Override
    public boolean isFinished(BotContext context) {
        return state == State.DONE;
    }

    @Override
    public void stop(BotContext context) {
        ClientPlayerInteractionManager interaction = context.client().interactionManager;
        if (interaction != null) {
            interaction.cancelBlockBreaking();
        }
        state = State.DONE;
    }

    // -------------------------------------------------------------------------
    // State handlers
    // -------------------------------------------------------------------------

    private void tickEquipPickaxe(BotContext context) {
        ClientPlayerEntity player = context.player();
        int invIndex = findInvIndex(player, Items.WOODEN_PICKAXE);

        if (invIndex == -1) {
            // Also accept stone pickaxe if someone already has one
            invIndex = findInvIndex(player, Items.STONE_PICKAXE);
        }

        if (invIndex == -1) {
            player.sendMessage(Text.literal("[SpeedrunBot] No pickaxe found — skipping stone mining"), true);
            transition(State.DONE);
            return;
        }

        if (invIndex >= 0 && invIndex <= 8) {
            // Already on hotbar — just select it
            selectHotbarSlot(player, invIndex);
        } else {
            // Main inventory: swap to hotbar slot 0
            ScreenHandler handler = player.playerScreenHandler;
            ClientPlayerInteractionManager interaction = context.client().interactionManager;
            if (handler != null && interaction != null) {
                int screenSlot = invIndex;
                interaction.clickSlot(handler.syncId, screenSlot, 0, SlotActionType.SWAP, player);
                selectHotbarSlot(player, 0);
            }
        }

        actionCooldown = 3;
        transition(State.DIG_DOWN);
    }

    private void tickDigDown(BotContext context) {
        ClientPlayerEntity player = context.player();
        ClientPlayerInteractionManager interaction = context.client().interactionManager;
        if (interaction == null) return;

        // Check cobblestone collected
        int currentCobble = countCobblestone(player);
        if (currentCobble >= COBBLESTONE_TARGET) {
            player.sendMessage(Text.literal("[SpeedrunBot] Collected " + currentCobble + " cobblestone!"), true);
            transition(State.DONE);
            return;
        }

        // Safety: don't dig too deep
        if (player.getY() < startY - MAX_DIG_DEPTH) {
            player.sendMessage(Text.literal("[SpeedrunBot] Reached max depth, stopping"), true);
            transition(State.DONE);
            return;
        }

        // Determine block to dig
        BlockPos feetPos = player.getBlockPos();
        BlockPos below = feetPos.down();

        // Safety: stop if standing on or about to mine lava/water
        if (isHazardous(context, feetPos) || isHazardous(context, below)) {
            player.sendMessage(Text.literal("[SpeedrunBot] Hazard detected — stopping mine down"), true);
            transition(State.DONE);
            return;
        }

        // Decide dig target: clear feet block if solid, else dig below
        BlockPos digTarget;
        BlockState feetState = context.world().getBlockState(feetPos);
        if (!feetState.isAir() && feetState.isSolidBlock(context.world(), feetPos)) {
            digTarget = feetPos;
        } else {
            digTarget = below;
        }

        BlockState digState = context.world().getBlockState(digTarget);
        if (digState.isAir()) {
            // Player is falling or block doesn't exist yet — aim pitch down and wait
            aimPitchDown(player);
            return;
        }

        // Aim pitch straight down
        aimPitchDown(player);

        // Mine the target block
        interaction.attackBlock(digTarget, Direction.UP);
        interaction.updateBlockBreakingProgress(digTarget, Direction.UP);
        player.swingHand(Hand.MAIN_HAND);
        context.actions().setAttack(true);

        // Update cobblestone count each tick for progress detection
        if (ticks % 5 == 0) {
            int newCount = countCobblestone(player);
            if (newCount > lastCobblestoneCount) {
                player.sendMessage(Text.literal("[SpeedrunBot] Cobblestone: " + newCount + " / " + COBBLESTONE_TARGET), true);
                lastCobblestoneCount = newCount;
            }
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static boolean isHazardous(BotContext context, BlockPos pos) {
        BlockState state = context.world().getBlockState(pos);
        return state.isOf(Blocks.LAVA) || state.isOf(Blocks.WATER)
            || state.isOf(Blocks.CAVE_AIR); // treat cave air as potential void proximity
    }

    private static void aimPitchDown(ClientPlayerEntity player) {
        float pitchStep = MathHelper.clamp(90.0F - player.getPitch(), -8.0F, 8.0F);
        player.setPitch(MathHelper.clamp(player.getPitch() + pitchStep, -90.0F, 90.0F));
    }

    private static int countCobblestone(ClientPlayerEntity player) {
        int total = 0;
        for (int i = 0; i < player.getInventory().size(); i++) {
            ItemStack s = player.getInventory().getStack(i);
            if (s.isOf(Items.COBBLESTONE) || s.isOf(Items.COBBLED_DEEPSLATE)) {
                total += s.getCount();
            }
        }
        return total;
    }

    private static int findInvIndex(ClientPlayerEntity player, net.minecraft.item.Item item) {
        for (int i = 0; i < player.getInventory().size(); i++) {
            if (player.getInventory().getStack(i).isOf(item)) return i;
        }
        return -1;
    }

    private static void selectHotbarSlot(ClientPlayerEntity player, int slot) {
        if (player.networkHandler != null) {
            player.networkHandler.sendPacket(new UpdateSelectedSlotC2SPacket(slot));
        }
    }

    private void transition(State next) {
        state = next;
    }
}
