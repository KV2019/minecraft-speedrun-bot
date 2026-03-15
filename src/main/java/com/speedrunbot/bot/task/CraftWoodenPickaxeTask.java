package com.speedrunbot.bot.task;

import com.speedrunbot.bot.BotContext;
import net.minecraft.block.Blocks;
import net.minecraft.client.gui.screen.ingame.CraftingScreen;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.ItemTags;
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

public final class CraftWoodenPickaxeTask implements BotTask {

    private enum State {
        SETUP,
        PLACE_TABLE,
        APPROACH_TABLE,
        OPEN_TABLE,
        CRAFT,
        CLOSE_SCREEN,
        DONE
    }

    // 3x3 crafting grid slot layout (CraftingScreenHandler):
    // result=0, grid slots 1-9 (row-major: 1,2,3 / 4,5,6 / 7,8,9)
    // Wooden pickaxe: planks at slots 1,2,3; sticks at slots 5,8
    private static final int RESULT_SLOT = 0;
    private static final int[] PLANK_SLOTS  = {1, 2, 3};
    private static final int[] STICK_SLOTS  = {5, 8};
    private static final int TABLE_RECOVER_MAX_TICKS = 80;
    private static final int TABLE_PICKUP_WINDOW_TICKS = 20;

    private State state;
    private int stateTicks;
    private int actionCooldown;

    private BlockPos tableTargetPos;   // where the crafting table block will be placed
    private BlockPos supportBlockPos;  // the block below tableTargetPos (placed on top of this)

    // Tracks which phase of CRAFT we are in
    private enum CraftPhase { PLANKS, STICKS, TAKE_RESULT }
    private CraftPhase craftPhase;
    private int craftStepIndex;
    private int tablePickupTicks;

    @Override
    public String name() {
        return "Craft Wooden Pickaxe";
    }

    @Override
    public void start(BotContext context) {
        state = State.SETUP;
        stateTicks = 0;
        actionCooldown = 0;
        tableTargetPos = null;
        supportBlockPos = null;
        craftPhase = CraftPhase.PLANKS;
        craftStepIndex = 0;
        tablePickupTicks = TABLE_PICKUP_WINDOW_TICKS;
        context.player().sendMessage(Text.literal("[SpeedrunBot] Crafting wooden pickaxe"), true);
    }

    @Override
    public void tick(BotContext context) {
        stateTicks++;

        if (actionCooldown > 0) {
            actionCooldown--;
            return;
        }

        switch (state) {
            case SETUP        -> tickSetup(context);
            case PLACE_TABLE  -> tickPlaceTable(context);
            case APPROACH_TABLE -> tickApproachTable(context);
            case OPEN_TABLE   -> tickOpenTable(context);
            case CRAFT        -> tickCraft(context);
            case CLOSE_SCREEN -> tickCloseScreen(context);
            case DONE         -> {}
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

    private void tickSetup(BotContext context) {
        ClientPlayerEntity player = context.player();

        // Already have a wooden pickaxe → done
        if (countExact(player, Items.WOODEN_PICKAXE) > 0) {
            transition(State.DONE);
            return;
        }

        // Need ingredients: 3 planks + 2 sticks
        int planks = countTaggedItems(player, ItemTags.PLANKS);
        int sticks  = countExact(player, Items.STICK);
        if (planks < 3 || sticks < 2) {
            player.sendMessage(Text.literal("[SpeedrunBot] Not enough materials for wooden pickaxe (need 3 planks, 2 sticks)"), true);
            transition(State.DONE);
            return;
        }

        // Need a crafting table item
        if (countExact(player, Items.CRAFTING_TABLE) == 0) {
            player.sendMessage(Text.literal("[SpeedrunBot] No crafting table in inventory"), true);
            transition(State.DONE);
            return;
        }

        // First try nearby cardinal spots, then force look-down and rescan wider area.
        BlockPos origin = player.getBlockPos();
        PlacementSpot spot = findPlacementSpot(context, origin, 1);
        if (spot == null) {
            forceLookDown(player);
            spot = findPlacementSpot(context, origin, 2);
        }

        if (spot != null) {
            tableTargetPos = spot.tablePos();
            supportBlockPos = spot.supportPos();
        }

        if (tableTargetPos == null) {
            player.sendMessage(Text.literal("[SpeedrunBot] No placement spot for crafting table"), true);
            transition(State.DONE);
            return;
        }

        // Equip crafting table: swap to hotbar 0
        int tableInvIndex = findInvIndex(player, Items.CRAFTING_TABLE);
        ScreenHandler handler = player.playerScreenHandler;
        ClientPlayerInteractionManager interaction = context.client().interactionManager;
        if (tableInvIndex >= 0 && interaction != null && handler != null) {
            if (tableInvIndex >= 9) {
                // main inventory → swap to hotbar slot 0
                int screenSlot = tableInvIndex;
                interaction.clickSlot(handler.syncId, screenSlot, 0, SlotActionType.SWAP, player);
            } else if (tableInvIndex != 0) {
                // hotbar slot N -> swap into hotbar slot 0
                int screenSlot = 36 + tableInvIndex;
                interaction.clickSlot(handler.syncId, screenSlot, 0, SlotActionType.SWAP, player);
            }
            selectHotbarSlot(player, 0);
        }
        actionCooldown = 3;
        transition(State.PLACE_TABLE);
    }

    private void tickPlaceTable(BotContext context) {
        // If already placed, proceed
        if (context.world().getBlockState(tableTargetPos).isOf(Blocks.CRAFTING_TABLE)) {
            transition(State.APPROACH_TABLE);
            return;
        }

        if (stateTicks > 20) {
            context.player().sendMessage(Text.literal("[SpeedrunBot] Failed to place crafting table"), true);
            transition(State.DONE);
            return;
        }

        // Look at the top face of the support block
        Vec3d aimPos = Vec3d.ofCenter(supportBlockPos).add(0, 0.5, 0);
        lookAt(context.player(), aimPos);

        // Only attempt placement once we are roughly aimed (alignment takes a few ticks)
        if (stateTicks >= 3) {
            ClientPlayerInteractionManager interaction = context.client().interactionManager;
            if (interaction != null) {
                selectHotbarSlot(context.player(), 0);
                Vec3d hitVec = Vec3d.ofCenter(supportBlockPos).add(0.0, 0.5, 0.0);
                BlockHitResult hitResult = new BlockHitResult(hitVec, Direction.UP, supportBlockPos, false);
                interaction.interactBlock(context.player(), Hand.MAIN_HAND, hitResult);
                context.player().swingHand(Hand.MAIN_HAND);
                actionCooldown = 4;
            }
        }
    }

    private void tickApproachTable(BotContext context) {
        if (!context.world().getBlockState(tableTargetPos).isOf(Blocks.CRAFTING_TABLE)) {
            // Table gone somehow
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
            craftPhase = CraftPhase.PLANKS;
            craftStepIndex = 0;
            actionCooldown = 2;
            transition(State.CRAFT);
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
                Vec3d hitVec = tableCenter;
                BlockHitResult hitResult = new BlockHitResult(hitVec, Direction.UP, tableTargetPos, false);
                interaction.interactBlock(context.player(), Hand.MAIN_HAND, hitResult);
                actionCooldown = 4;
            }
        }
    }

    private void tickCraft(BotContext context) {
        if (!(context.client().currentScreen instanceof CraftingScreen)) {
            // Screen closed unexpectedly
            transition(State.CLOSE_SCREEN);
            return;
        }

        ScreenHandler handler = context.player().currentScreenHandler;
        ClientPlayerInteractionManager interaction = context.client().interactionManager;
        if (handler == null || interaction == null) {
            return;
        }

        int syncId = handler.syncId;
        ClientPlayerEntity player = context.player();

        switch (craftPhase) {
            case PLANKS -> {
                if (craftStepIndex < PLANK_SLOTS.length) {
                    int srcSlot = findCraftingScreenSlot(player, ItemTags.PLANKS, true);
                    if (srcSlot == -1) {
                        transition(State.CLOSE_SCREEN);
                        return;
                    }
                    placeSingleItem(interaction, handler, player, srcSlot, PLANK_SLOTS[craftStepIndex]);
                    craftStepIndex++;
                    actionCooldown = 2;
                } else {
                    craftPhase = CraftPhase.STICKS;
                    craftStepIndex = 0;
                    actionCooldown = 1;
                }
            }
            case STICKS -> {
                if (craftStepIndex < STICK_SLOTS.length) {
                    int srcSlot = findCraftingScreenSlotExact(player, Items.STICK);
                    if (srcSlot == -1) {
                        transition(State.CLOSE_SCREEN);
                        return;
                    }
                    placeSingleItem(interaction, handler, player, srcSlot, STICK_SLOTS[craftStepIndex]);
                    craftStepIndex++;
                    actionCooldown = 2;
                } else {
                    craftPhase = CraftPhase.TAKE_RESULT;
                    actionCooldown = 2;
                }
            }
            case TAKE_RESULT -> {
                if (!handler.getSlot(RESULT_SLOT).getStack().isEmpty()) {
                    interaction.clickSlot(syncId, RESULT_SLOT, 0, SlotActionType.QUICK_MOVE, player);
                    player.sendMessage(Text.literal("[SpeedrunBot] Crafted wooden pickaxe!"), true);
                    actionCooldown = 2;
                    transition(State.CLOSE_SCREEN);
                } else {
                    // Result slot empty — recipe may not have registered yet — wait a few ticks
                    if (stateTicks > 60) {
                        player.sendMessage(Text.literal("[SpeedrunBot] Pickaxe recipe failed"), true);
                        transition(State.CLOSE_SCREEN);
                    }
                }
            }
        }
    }

    private void tickCloseScreen(BotContext context) {
        if (context.client().currentScreen != null) {
            context.player().closeHandledScreen();
        }

        if (tableTargetPos == null || stateTicks > TABLE_RECOVER_MAX_TICKS) {
            actionCooldown = 2;
            transition(State.DONE);
            return;
        }

        boolean hasTableInInventory = countExact(context.player(), Items.CRAFTING_TABLE) > 0;
        boolean tableStillPlaced = context.world().getBlockState(tableTargetPos).isOf(Blocks.CRAFTING_TABLE);

        Vec3d tableCenter = Vec3d.ofCenter(tableTargetPos);
        double distSq = context.player().squaredDistanceTo(tableCenter.x, tableCenter.y, tableCenter.z);

        if (tableStillPlaced) {
            if (distSq > 4.5 * 4.5) {
                moveToward(context, tableCenter, 8);
                return;
            }

            ClientPlayerInteractionManager interaction = context.client().interactionManager;
            if (interaction != null) {
                lookAt(context.player(), tableCenter);
                Direction hitSide = sideClosestToPlayer(context.player(), tableCenter);
                interaction.attackBlock(tableTargetPos, hitSide);
                interaction.updateBlockBreakingProgress(tableTargetPos, hitSide);
                context.player().swingHand(Hand.MAIN_HAND);
                context.actions().setAttack(true);
            }
            return;
        }

        if (hasTableInInventory) {
            actionCooldown = 2;
            transition(State.DONE);
            return;
        }

        if (tablePickupTicks > 0) {
            tablePickupTicks--;
            moveToward(context, tableCenter, 0);
            return;
        }

        actionCooldown = 2;
        transition(State.DONE);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private record PlacementSpot(BlockPos tablePos, BlockPos supportPos) {}

    private static PlacementSpot findPlacementSpot(BotContext context, BlockPos origin, int radius) {
        for (int r = 1; r <= radius; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.abs(dx) + Math.abs(dz) != r) {
                        continue;
                    }

                    BlockPos candidate = origin.add(dx, 0, dz);
                    BlockPos below = candidate.down();
                    if (!context.world().getBlockState(candidate).isAir()) {
                        continue;
                    }
                    if (!context.world().getBlockState(candidate.up()).isAir()) {
                        continue;
                    }
                    if (context.world().getBlockState(below).isAir()) {
                        continue;
                    }

                    return new PlacementSpot(candidate.toImmutable(), below.toImmutable());
                }
            }
        }

        return null;
    }

    private static void forceLookDown(ClientPlayerEntity player) {
        player.setPitch(Math.max(player.getPitch(), 65.0F));
    }

    private void placeSingleItem(
            ClientPlayerInteractionManager interaction,
            ScreenHandler handler,
            ClientPlayerEntity player,
            int sourceSlot,
            int craftSlot) {
        // Clear cursor if dirty
        if (!handler.getCursorStack().isEmpty()) {
            interaction.clickSlot(handler.syncId, sourceSlot, 0, SlotActionType.PICKUP, player);
        }
        // Pick up full stack from source
        interaction.clickSlot(handler.syncId, sourceSlot, 0, SlotActionType.PICKUP, player);
        // Right-click to place exactly 1 into craft slot
        interaction.clickSlot(handler.syncId, craftSlot, 1, SlotActionType.PICKUP, player);
        // Put remainder back
        interaction.clickSlot(handler.syncId, sourceSlot, 0, SlotActionType.PICKUP, player);
    }

    /**
     * Find the screen slot index inside a CraftingScreenHandler for an item matching a tag.
     * CraftingScreenHandler slot layout: 0=result, 1-9=grid, 10-45=player inv.
     *   player main inv (invIndex 9-35) → screenSlot = invIndex + 1
     *   player hotbar   (invIndex 0-8)  → screenSlot = invIndex + 37
     */
    private int findCraftingScreenSlot(ClientPlayerEntity player,
            net.minecraft.registry.tag.TagKey<net.minecraft.item.Item> tag,
            boolean skipCraftGrid) {
        for (int invIndex = 0; invIndex < player.getInventory().size(); invIndex++) {
            ItemStack stack = player.getInventory().getStack(invIndex);
            if (!stack.isEmpty() && stack.isIn(tag)) {
                return invIndexToCraftingScreen(invIndex);
            }
        }
        return -1;
    }

    private int findCraftingScreenSlotExact(ClientPlayerEntity player, net.minecraft.item.Item item) {
        for (int invIndex = 0; invIndex < player.getInventory().size(); invIndex++) {
            ItemStack stack = player.getInventory().getStack(invIndex);
            if (!stack.isEmpty() && stack.isOf(item)) {
                return invIndexToCraftingScreen(invIndex);
            }
        }
        return -1;
    }

    /** invIndex → CraftingScreenHandler screen slot index */
    private static int invIndexToCraftingScreen(int invIndex) {
        if (invIndex >= 9 && invIndex <= 35) return invIndex + 1;   // main inventory
        if (invIndex >= 0 && invIndex <= 8)  return invIndex + 37;  // hotbar
        return -1;
    }

    private static int findInvIndex(ClientPlayerEntity player, net.minecraft.item.Item item) {
        for (int i = 0; i < player.getInventory().size(); i++) {
            if (player.getInventory().getStack(i).isOf(item)) return i;
        }
        return -1;
    }

    private static int countExact(ClientPlayerEntity player, net.minecraft.item.Item item) {
        int total = 0;
        for (int i = 0; i < player.getInventory().size(); i++) {
            ItemStack s = player.getInventory().getStack(i);
            if (s.isOf(item)) total += s.getCount();
        }
        return total;
    }

    private static int countTaggedItems(ClientPlayerEntity player,
            net.minecraft.registry.tag.TagKey<net.minecraft.item.Item> tag) {
        int total = 0;
        for (int i = 0; i < player.getInventory().size(); i++) {
            ItemStack s = player.getInventory().getStack(i);
            if (s.isIn(tag)) total += s.getCount();
        }
        return total;
    }

    private static void selectHotbarSlot(ClientPlayerEntity player, int slot) {
        player.getInventory().setSelectedSlot(slot);
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

    private static Direction sideClosestToPlayer(ClientPlayerEntity player, Vec3d blockCenter) {
        double dx = player.getX() - blockCenter.x;
        double dy = player.getEyeY() - blockCenter.y;
        double dz = player.getZ() - blockCenter.z;
        return Direction.getFacing(dx, dy, dz);
    }
}
