package com.speedrunbot.bot.task;

import com.speedrunbot.bot.BotContext;
import net.minecraft.block.Blocks;
import net.minecraft.client.gui.screen.ingame.CraftingScreen;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

public final class CraftStoneToolsTask implements BotTask {

    private enum State {
        FIND_TABLE,
        SETUP,
        PLACE_TABLE,
        APPROACH_TABLE,
        OPEN_TABLE,
        CRAFT_PICKAXE,
        CRAFT_AXE,
        CLOSE_SCREEN,
        DONE
    }

    // 3x3 CraftingScreenHandler slot layout:
    //   result = 0
    //   grid row 0: 1, 2, 3
    //   grid row 1: 4, 5, 6
    //   grid row 2: 7, 8, 9
    //
    // Stone pickaxe:  1=cobble, 2=cobble, 3=cobble,  5=stick, 8=stick
    // Stone axe:      1=cobble, 2=cobble, 4=stick,   5=cobble, 7=stick
    private static final int RESULT_SLOT = 0;
    private static final int[] PICKAXE_COBBLE_SLOTS = {1, 2, 3};
    private static final int[] PICKAXE_STICK_SLOTS  = {5, 8};
    private static final int[] AXE_COBBLE_SLOTS     = {1, 2, 5};
    private static final int[] AXE_STICK_SLOTS      = {4, 7};
    private static final int[] ALL_GRID_SLOTS        = {1, 2, 3, 4, 5, 6, 7, 8, 9};

    private static final int FIND_TABLE_RADIUS = 5;

    private State state;
    private int stateTicks;
    private int actionCooldown;

    private BlockPos tableTargetPos;
    private BlockPos supportBlockPos;

    private enum CraftPhase { COBBLE, STICKS, TAKE_RESULT }
    private CraftPhase craftPhase;
    private int craftStepIndex;
    // Which recipe slots we're filling (set before entering CRAFT_PICKAXE / CRAFT_AXE)
    private int[] activeCobbleSlots;
    private int[] activeStickSlots;

    @Override
    public String name() {
        return "Craft Stone Tools";
    }

    @Override
    public void start(BotContext context) {
        state = State.FIND_TABLE;
        stateTicks = 0;
        actionCooldown = 0;
        tableTargetPos = null;
        supportBlockPos = null;
        craftPhase = CraftPhase.COBBLE;
        craftStepIndex = 0;
        activeCobbleSlots = null;
        activeStickSlots = null;
        context.player().sendMessage(Text.literal("[SpeedrunBot] Crafting stone tools"), true);
    }

    @Override
    public void tick(BotContext context) {
        stateTicks++;

        if (actionCooldown > 0) {
            actionCooldown--;
            return;
        }

        switch (state) {
            case FIND_TABLE     -> tickFindTable(context);
            case SETUP          -> tickSetup(context);
            case PLACE_TABLE    -> tickPlaceTable(context);
            case APPROACH_TABLE -> tickApproachTable(context);
            case OPEN_TABLE     -> tickOpenTable(context);
            case CRAFT_PICKAXE  -> tickCraftPickaxe(context);
            case CRAFT_AXE      -> tickCraftAxe(context);
            case CLOSE_SCREEN   -> tickCloseScreen(context);
            case DONE           -> {}
        }
    }

    @Override
    public boolean isFinished(BotContext context) {
        return state == State.DONE;
    }

    @Override
    public void stop(BotContext context) {
        if (context.client().currentScreen != null) {
            context.player().closeHandledScreen();
        }
        state = State.DONE;
    }

    // -------------------------------------------------------------------------
    // State handlers
    // -------------------------------------------------------------------------

    private void tickFindTable(BotContext context) {
        // Scan nearby for an existing crafting table
        BlockPos origin = context.player().getBlockPos();
        for (int dx = -FIND_TABLE_RADIUS; dx <= FIND_TABLE_RADIUS; dx++) {
            for (int dy = -2; dy <= 2; dy++) {
                for (int dz = -FIND_TABLE_RADIUS; dz <= FIND_TABLE_RADIUS; dz++) {
                    BlockPos pos = origin.add(dx, dy, dz);
                    if (context.world().getBlockState(pos).isOf(Blocks.CRAFTING_TABLE)) {
                        tableTargetPos = pos.toImmutable();
                        transition(State.APPROACH_TABLE);
                        return;
                    }
                }
            }
        }
        // No table found — need to place one
        transition(State.SETUP);
    }

    private void tickSetup(BotContext context) {
        ClientPlayerEntity player = context.player();

        // Check ingredients (pickaxe: 3 cobble+2 sticks; axe: 3 cobble+2 sticks → max 6+4)
        int cobble = countExact(player, Items.COBBLESTONE) + countExact(player, Items.COBBLED_DEEPSLATE);
        int sticks = countExact(player, Items.STICK);
        if (cobble < 3 || sticks < 2) {
            player.sendMessage(Text.literal("[SpeedrunBot] Not enough materials for stone tools"), true);
            transition(State.DONE);
            return;
        }

        // Need a crafting table to place
        if (countExact(player, Items.CRAFTING_TABLE) == 0) {
            player.sendMessage(Text.literal("[SpeedrunBot] No crafting table in inventory"), true);
            transition(State.DONE);
            return;
        }

        // Find placement spot
        BlockPos origin = player.getBlockPos();
        for (Direction dir : new Direction[]{Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST}) {
            BlockPos candidate = origin.offset(dir);
            BlockPos below = candidate.down();
            if (context.world().getBlockState(candidate).isAir()
                    && !context.world().getBlockState(below).isAir()) {
                tableTargetPos = candidate.toImmutable();
                supportBlockPos = below.toImmutable();
                break;
            }
        }

        if (tableTargetPos == null) {
            player.sendMessage(Text.literal("[SpeedrunBot] No placement spot for crafting table"), true);
            transition(State.DONE);
            return;
        }

        // Equip crafting table to hotbar 0
        int tableInvIndex = findInvIndex(player, Items.CRAFTING_TABLE);
        ScreenHandler handler = player.playerScreenHandler;
        ClientPlayerInteractionManager interaction = context.client().interactionManager;
        if (tableInvIndex >= 0 && interaction != null && handler != null) {
            if (tableInvIndex >= 9) {
                interaction.clickSlot(handler.syncId, tableInvIndex, 0, SlotActionType.SWAP, player);
            }
            selectHotbarSlot(player, 0);
        }
        actionCooldown = 3;
        transition(State.PLACE_TABLE);
    }

    private void tickPlaceTable(BotContext context) {
        if (context.world().getBlockState(tableTargetPos).isOf(Blocks.CRAFTING_TABLE)) {
            transition(State.APPROACH_TABLE);
            return;
        }

        if (stateTicks > 20) {
            context.player().sendMessage(Text.literal("[SpeedrunBot] Failed to place crafting table"), true);
            transition(State.DONE);
            return;
        }

        Vec3d aimPos = Vec3d.ofCenter(supportBlockPos).add(0, 0.5, 0);
        lookAt(context.player(), aimPos);

        if (stateTicks >= 3) {
            ClientPlayerInteractionManager interaction = context.client().interactionManager;
            if (interaction != null) {
                Vec3d hitVec = Vec3d.of(tableTargetPos);
                BlockHitResult hitResult = new BlockHitResult(hitVec, Direction.UP, supportBlockPos, false);
                interaction.interactBlock(context.player(), Hand.MAIN_HAND, hitResult);
                actionCooldown = 4;
            }
        }
    }

    private void tickApproachTable(BotContext context) {
        if (!context.world().getBlockState(tableTargetPos).isOf(Blocks.CRAFTING_TABLE)) {
            transition(State.DONE);
            return;
        }

        Vec3d tableCenter = Vec3d.ofCenter(tableTargetPos);
        double distSq = context.player().squaredDistanceTo(tableCenter.x, tableCenter.y, tableCenter.z);
        if (distSq <= 3.5 * 3.5) {
            transition(State.OPEN_TABLE);
            return;
        }

        moveToward(context, tableCenter, 12);
    }

    private void tickOpenTable(BotContext context) {
        if (!context.world().getBlockState(tableTargetPos).isOf(Blocks.CRAFTING_TABLE)) {
            transition(State.DONE);
            return;
        }

        if (context.client().currentScreen instanceof CraftingScreen) {
            // Ready to craft — start with pickaxe
            activeCobbleSlots = PICKAXE_COBBLE_SLOTS;
            activeStickSlots  = PICKAXE_STICK_SLOTS;
            craftPhase = CraftPhase.COBBLE;
            craftStepIndex = 0;
            actionCooldown = 2;
            transition(State.CRAFT_PICKAXE);
            return;
        }

        if (stateTicks > 40) {
            context.player().sendMessage(Text.literal("[SpeedrunBot] Failed to open crafting table"), true);
            transition(State.DONE);
            return;
        }

        Vec3d tableCenter = Vec3d.ofCenter(tableTargetPos);
        lookAt(context.player(), tableCenter);

        if (stateTicks >= 3) {
            ClientPlayerInteractionManager interaction = context.client().interactionManager;
            if (interaction != null) {
                BlockHitResult hitResult = new BlockHitResult(tableCenter, Direction.UP, tableTargetPos, false);
                interaction.interactBlock(context.player(), Hand.MAIN_HAND, hitResult);
                actionCooldown = 4;
            }
        }
    }

    private void tickCraftPickaxe(BotContext context) {
        if (!(context.client().currentScreen instanceof CraftingScreen)) {
            transition(State.CLOSE_SCREEN);
            return;
        }

        ScreenHandler handler = context.player().currentScreenHandler;
        ClientPlayerInteractionManager interaction = context.client().interactionManager;
        if (handler == null || interaction == null) return;

        boolean done = runCraftStep(context, handler, interaction, Items.COBBLESTONE, Items.STICK);
        if (done) {
            context.player().sendMessage(Text.literal("[SpeedrunBot] Crafted stone pickaxe!"), true);
            // Clear grid then proceed to axe
            clearCraftGrid(context, handler, interaction);
            activeCobbleSlots = AXE_COBBLE_SLOTS;
            activeStickSlots  = AXE_STICK_SLOTS;
            craftPhase = CraftPhase.COBBLE;
            craftStepIndex = 0;
            actionCooldown = 3;
            transition(State.CRAFT_AXE);
        }
    }

    private void tickCraftAxe(BotContext context) {
        if (!(context.client().currentScreen instanceof CraftingScreen)) {
            transition(State.CLOSE_SCREEN);
            return;
        }

        ScreenHandler handler = context.player().currentScreenHandler;
        ClientPlayerInteractionManager interaction = context.client().interactionManager;
        if (handler == null || interaction == null) return;

        boolean done = runCraftStep(context, handler, interaction, Items.COBBLESTONE, Items.STICK);
        if (done) {
            context.player().sendMessage(Text.literal("[SpeedrunBot] Crafted stone axe!"), true);
            transition(State.CLOSE_SCREEN);
        }
    }

    private void tickCloseScreen(BotContext context) {
        if (context.client().currentScreen != null) {
            context.player().closeHandledScreen();
        }
        actionCooldown = 2;
        transition(State.DONE);
    }

    // -------------------------------------------------------------------------
    // Generic recipe step runner (uses activeCobbleSlots / activeStickSlots)
    // Returns true when QUICK_MOVE of result has been executed.
    // -------------------------------------------------------------------------

    /**
     * Runs one step of the current recipe.
     * @return true when the result item has been taken (recipe complete).
     */
    private boolean runCraftStep(BotContext context, ScreenHandler handler,
            ClientPlayerInteractionManager interaction, Item cobbleItem, Item stickItem) {

        ClientPlayerEntity player = context.player();
        int syncId = handler.syncId;

        switch (craftPhase) {
            case COBBLE -> {
                if (craftStepIndex < activeCobbleSlots.length) {
                    int srcSlot = findCraftingScreenSlotExact(player, cobbleItem);
                    if (srcSlot == -1) {
                        // Try cobbled deepslate too
                        srcSlot = findCraftingScreenSlotExact(player, Items.COBBLED_DEEPSLATE);
                    }
                    if (srcSlot == -1) {
                        transition(State.CLOSE_SCREEN);
                        return false;
                    }
                    placeSingleItem(interaction, handler, player, srcSlot, activeCobbleSlots[craftStepIndex]);
                    craftStepIndex++;
                    actionCooldown = 2;
                } else {
                    craftPhase = CraftPhase.STICKS;
                    craftStepIndex = 0;
                    actionCooldown = 1;
                }
                return false;
            }
            case STICKS -> {
                if (craftStepIndex < activeStickSlots.length) {
                    int srcSlot = findCraftingScreenSlotExact(player, stickItem);
                    if (srcSlot == -1) {
                        transition(State.CLOSE_SCREEN);
                        return false;
                    }
                    placeSingleItem(interaction, handler, player, srcSlot, activeStickSlots[craftStepIndex]);
                    craftStepIndex++;
                    actionCooldown = 2;
                } else {
                    craftPhase = CraftPhase.TAKE_RESULT;
                    actionCooldown = 2;
                }
                return false;
            }
            case TAKE_RESULT -> {
                if (!handler.getSlot(RESULT_SLOT).getStack().isEmpty()) {
                    interaction.clickSlot(syncId, RESULT_SLOT, 0, SlotActionType.QUICK_MOVE, player);
                    actionCooldown = 2;
                    return true;
                } else if (stateTicks > 60) {
                    // Recipe didn't register
                    transition(State.CLOSE_SCREEN);
                }
                return false;
            }
        }
        return false;
    }

    private void clearCraftGrid(BotContext context, ScreenHandler handler,
            ClientPlayerInteractionManager interaction) {
        for (int slot : ALL_GRID_SLOTS) {
            if (!handler.getSlot(slot).getStack().isEmpty()) {
                interaction.clickSlot(handler.syncId, slot, 0, SlotActionType.QUICK_MOVE, context.player());
            }
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void placeSingleItem(
            ClientPlayerInteractionManager interaction,
            ScreenHandler handler,
            ClientPlayerEntity player,
            int sourceSlot,
            int craftSlot) {
        if (!handler.getCursorStack().isEmpty()) {
            interaction.clickSlot(handler.syncId, sourceSlot, 0, SlotActionType.PICKUP, player);
        }
        interaction.clickSlot(handler.syncId, sourceSlot, 0, SlotActionType.PICKUP, player);
        interaction.clickSlot(handler.syncId, craftSlot, 1, SlotActionType.PICKUP, player);
        interaction.clickSlot(handler.syncId, sourceSlot, 0, SlotActionType.PICKUP, player);
    }

    private static int findCraftingScreenSlotExact(ClientPlayerEntity player, Item item) {
        for (int invIndex = 0; invIndex < player.getInventory().size(); invIndex++) {
            ItemStack stack = player.getInventory().getStack(invIndex);
            if (!stack.isEmpty() && stack.isOf(item)) {
                return invIndexToCraftingScreen(invIndex);
            }
        }
        return -1;
    }

    private static int invIndexToCraftingScreen(int invIndex) {
        if (invIndex >= 9 && invIndex <= 35) return invIndex + 1;
        if (invIndex >= 0 && invIndex <= 8)  return invIndex + 37;
        return -1;
    }

    private static int findInvIndex(ClientPlayerEntity player, Item item) {
        for (int i = 0; i < player.getInventory().size(); i++) {
            if (player.getInventory().getStack(i).isOf(item)) return i;
        }
        return -1;
    }

    private static int countExact(ClientPlayerEntity player, Item item) {
        int total = 0;
        for (int i = 0; i < player.getInventory().size(); i++) {
            ItemStack s = player.getInventory().getStack(i);
            if (s.isOf(item)) total += s.getCount();
        }
        return total;
    }

    private static void selectHotbarSlot(ClientPlayerEntity player, int slot) {
        if (player.networkHandler != null) {
            player.networkHandler.sendPacket(new UpdateSelectedSlotC2SPacket(slot));
        }
    }

    private void transition(State next) {
        state = next;
        stateTicks = 0;
    }

    private static void lookAt(ClientPlayerEntity player, Vec3d target) {
        double dx = target.x - player.getX();
        double dy = target.y - player.getEyeY();
        double dz = target.z - player.getZ();
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        float desiredYaw   = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float desiredPitch = (float) (-Math.toDegrees(Math.atan2(dy, horizontal)));
        float yawStep   = MathHelper.clamp(MathHelper.wrapDegrees(desiredYaw   - player.getYaw()),   -8.0F, 8.0F);
        float pitchStep = MathHelper.clamp(desiredPitch - player.getPitch(), -6.0F, 6.0F);
        player.setYaw(player.getYaw() + yawStep);
        player.setPitch(MathHelper.clamp(player.getPitch() + pitchStep, -80.0F, 80.0F));
    }

    private static void moveToward(BotContext context, Vec3d target, int jumpPeriodTicks) {
        ClientPlayerEntity player = context.player();
        context.actions().setForward(true);
        context.actions().setSprint(true);
        double dx = target.x - player.getX();
        double dz = target.z - player.getZ();
        float desiredYaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float yawDelta = MathHelper.wrapDegrees(desiredYaw - player.getYaw());
        player.setYaw(player.getYaw() + MathHelper.clamp(yawDelta, -3.0F, 3.0F));
        player.setPitch(MathHelper.clamp(player.getPitch(), -25.0F, 25.0F));
        if (yawDelta > 35.0F) context.actions().setRight(true);
        else if (yawDelta < -35.0F) context.actions().setLeft(true);
        double horizontalDistSq = dx * dx + dz * dz;
        if (player.isOnGround() && jumpPeriodTicks > 0 && horizontalDistSq > 1.2 * 1.2
                && player.age % jumpPeriodTicks == 0) {
            context.actions().setJump(true);
        }
    }
}
