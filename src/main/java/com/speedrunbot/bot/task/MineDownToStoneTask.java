package com.speedrunbot.bot.task;

import com.speedrunbot.bot.BotContext;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

public final class MineDownToStoneTask implements BotTask {
    private enum State {
        EQUIP_PICKAXE,
        DIG_TO_STONE,
        MINE_TUNNEL,
        RETURN_TO_SHAFT,
        EQUIP_TOWER_BLOCK,
        TOWER_UP,
        STAIRCASE_UP,
        MINE_UP,
        DONE
    }

    private static final int COBBLESTONE_TARGET = 16;
    private static final int BLOCKED_COBBLESTONE_TARGET_MIN = 12;
    private static final int BLOCKED_COBBLESTONE_TARGET_MAX = 20;
    private static final int INITIAL_STONE_TARGET = 2;
    private static final int MAX_DIG_DEPTH = 20;
    private static final int SURFACE_MARGIN = 1;
    private static final int MAX_DIG_TO_STONE_TICKS = 20 * 30;
    private static final int MAX_TUNNEL_TICKS = 20 * 45;
    private static final int MAX_RETURN_TICKS = 20 * 8;
    private static final int RETURN_STUCK_TICKS = 20;
    private static final int RETURN_ALT_TRY_TICKS = 12;
    private static final int TUNNEL_DIR_LOCK_TICKS = 12;
    private static final int MAX_STAIRCASE_TICKS = 20 * 30;
    private static final int STAIRCASE_NO_PROGRESS_TICKS = 24;
    private static final int MAX_STAIR_DIR_FAILS = 4;
    private static final int MAX_MINE_UP_TICKS = 20 * 20;
    private static final int WATER_CONTACT_ESCAPE_TICKS = 14;
    private static final Item[] TOWER_BLOCKS = {
        Items.DIRT,
        Items.GRASS_BLOCK,
        Items.COARSE_DIRT,
        Items.PODZOL,
        Items.ROOTED_DIRT
    };

    private State state;
    private int ticks;
    private int actionCooldown;
    private int startSurfaceY;
    private int startingCobblestone;
    private int lastCobblestoneCount;
    private BlockPos shaftTopPos;
    private Direction tunnelDirection;
    private Direction staircaseDirection;
    private int stateTicks;
    private BlockPos lastReturnPos;
    private int returnStuckTicks;
    private int returnAltTicks;
    private int tunnelDirectionLockTicks;
    private int lastStairY;
    private int stairNoProgressTicks;
    private int stairDirectionFailCount;
    private boolean blockedFallbackUsed;
    private String exitReason;
    private int waterContactTicks;
    private boolean waterEscapeAnnounced;

    @Override
    public String name() {
        return "Mine Down to Stone";
    }

    @Override
    public void start(BotContext context) {
        state = State.EQUIP_PICKAXE;
        ticks = 0;
        actionCooldown = 0;
        startSurfaceY = context.player().getBlockY();
        startingCobblestone = countCobblestone(context.player());
        lastCobblestoneCount = startingCobblestone;
        shaftTopPos = context.player().getBlockPos().toImmutable();
        tunnelDirection = context.player().getHorizontalFacing();
        staircaseDirection = context.player().getHorizontalFacing();
        stateTicks = 0;
        lastReturnPos = null;
        returnStuckTicks = 0;
        returnAltTicks = 0;
        tunnelDirectionLockTicks = 0;
        lastStairY = context.player().getBlockY();
        stairNoProgressTicks = 0;
        stairDirectionFailCount = 0;
        blockedFallbackUsed = false;
        exitReason = "running";
        waterContactTicks = 0;
        waterEscapeAnnounced = false;
        context.player().sendMessage(Text.literal("[SpeedrunBot] Mining to stone, then tunneling"), true);
    }

    @Override
    public void tick(BotContext context) {
        ticks++;
        stateTicks++;

        if (handleWaterEmergency(context)) {
            return;
        }

        if (actionCooldown > 0) {
            actionCooldown--;
            return;
        }

        switch (state) {
            case EQUIP_PICKAXE -> tickEquipPickaxe(context);
            case DIG_TO_STONE -> tickDigToStone(context);
            case MINE_TUNNEL -> tickMineTunnel(context);
            case RETURN_TO_SHAFT -> tickReturnToShaft(context);
            case EQUIP_TOWER_BLOCK -> tickEquipTowerBlock(context);
            case TOWER_UP -> tickTowerUp(context);
            case STAIRCASE_UP -> tickStaircaseUp(context);
            case MINE_UP -> tickMineUp(context);
            case DONE -> {
            }
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
        if (!"running".equals(exitReason)) {
            emitSummary(context, "stopped");
        }
        state = State.DONE;
    }

    private void tickEquipPickaxe(BotContext context) {
        if (!equipItem(context, Items.NETHERITE_PICKAXE, Items.DIAMOND_PICKAXE, Items.IRON_PICKAXE, Items.STONE_PICKAXE, Items.GOLDEN_PICKAXE, Items.WOODEN_PICKAXE)) {
            context.player().sendMessage(Text.literal("[SpeedrunBot] No pickaxe found"), true);
            finish(context, "no_pickaxe");
            transition(State.DONE);
            return;
        }

        actionCooldown = 3;
        transition(State.DIG_TO_STONE);
    }

    private void tickDigToStone(BotContext context) {
        ClientPlayerEntity player = context.player();
        if (stateTicks > MAX_DIG_TO_STONE_TICKS) {
            if (gainedCobblestone(player) >= BLOCKED_COBBLESTONE_TARGET_MIN) {
                blockedFallbackUsed = true;
                transition(State.RETURN_TO_SHAFT, "dig_timeout_partial_return");
            } else {
                transition(State.EQUIP_TOWER_BLOCK, "dig_timeout_escape");
            }
            return;
        }

        if (player.getBlockY() < startSurfaceY - MAX_DIG_DEPTH) {
            if (isStoneEnvironment(context, player.getBlockPos())) {
                Direction chosen = pickStoneTunnelDirection(context, player.getBlockPos());
                if (chosen != null) {
                    tunnelDirection = chosen;
                    tunnelDirectionLockTicks = TUNNEL_DIR_LOCK_TICKS;
                    transition(State.MINE_TUNNEL, "depth_limit_stone_ready");
                    return;
                }
            }

            player.sendMessage(Text.literal("[SpeedrunBot] Reached dig depth limit"), true);
            transition(State.EQUIP_TOWER_BLOCK, "depth_limit_escape");
            return;
        }

        BlockPos belowPos = player.getBlockPos().down();
        if (isHazardous(context, belowPos)) {
            player.sendMessage(Text.literal("[SpeedrunBot] Hazard below, returning"), true);
            transition(State.EQUIP_TOWER_BLOCK, "hazard_below");
            return;
        }

        if (wouldExposeFluid(context, belowPos)) {
            blockedFallbackUsed = true;
            player.sendMessage(Text.literal("[SpeedrunBot] Water risk below, escaping upward"), true);
            transition(State.EQUIP_TOWER_BLOCK, "fluid_risk_below");
            return;
        }

        // Only try to transition once we have enough cobblestone AND a valid stone corridor.
        if (gainedCobblestone(player) >= INITIAL_STONE_TARGET && isStoneEnvironment(context, player.getBlockPos())) {
            Direction chosen = pickStoneTunnelDirection(context, player.getBlockPos());
            if (chosen != null) {
                tunnelDirection = chosen;
                tunnelDirectionLockTicks = TUNNEL_DIR_LOCK_TICKS;
                player.sendMessage(Text.literal("[SpeedrunBot] Found stone, mining sideways"), true);
                transition(State.MINE_TUNNEL, "stone_corridor_found");
                return;
            }
            // No valid direction yet — keep digging deeper.
        }

        // Centre on the block before digging straight down.
        Vec3d blockCenter = Vec3d.ofBottomCenter(player.getBlockPos());
        double offX = Math.abs(player.getX() - blockCenter.x);
        double offZ = Math.abs(player.getZ() - blockCenter.z);
        if (offX > 0.2 || offZ > 0.2) {
            moveToward(context, blockCenter, 0);
            return;
        }

        if (mineBlock(context, belowPos, Direction.UP, true)) {
            announceCobbleProgress(player);
        }
    }

    private void tickMineTunnel(BotContext context) {
        ClientPlayerEntity player = context.player();
        int currentCobble = countCobblestone(player);
        if (currentCobble >= COBBLESTONE_TARGET) {
            player.sendMessage(Text.literal("[SpeedrunBot] Collected enough stone, returning"), true);
            transition(State.RETURN_TO_SHAFT, "target_reached");
            return;
        }

        if (stateTicks > MAX_TUNNEL_TICKS) {
            if (currentCobble >= BLOCKED_COBBLESTONE_TARGET_MIN) {
                blockedFallbackUsed = true;
                player.sendMessage(Text.literal("[SpeedrunBot] Tunnel blocked, returning with partial stone"), true);
                transition(State.RETURN_TO_SHAFT, "tunnel_timeout_partial");
            } else {
                transition(State.RETURN_TO_SHAFT, "tunnel_timeout_low_yield");
            }
            return;
        }

        if (tunnelDirectionLockTicks > 0) {
            tunnelDirectionLockTicks--;
        }

        BlockPos basePos = player.getBlockPos();
        BlockPos frontFeet = basePos.offset(tunnelDirection);
        BlockPos frontHead = frontFeet.up();

        boolean frontFeetAir = context.world().getBlockState(frontFeet).isAir();
        boolean frontHeadAir = context.world().getBlockState(frontHead).isAir();

        // Both blocks cleared — advance forward.
        if (frontFeetAir && frontHeadAir) {
            moveToward(context, Vec3d.ofBottomCenter(frontFeet), 0);
            return;
        }

        // Only re-evaluate tunnel direction when BOTH blocks are still solid (not mid-mine).
        // If we re-evaluate after mining the head (now air), isStoneTunnelPair would return
        // false and abort prematurely.
        if (!frontFeetAir && !frontHeadAir) {
            if (!isStoneTunnelPair(context, frontFeet, frontHead) && tunnelDirectionLockTicks == 0) {
                Direction next = pickStoneTunnelDirection(context, basePos);
                if (next == null) {
                    if (currentCobble >= BLOCKED_COBBLESTONE_TARGET_MIN) {
                        blockedFallbackUsed = true;
                        player.sendMessage(Text.literal("[SpeedrunBot] Tunnel left stone layer, returning"), true);
                    }
                    transition(State.RETURN_TO_SHAFT, "no_tunnel_direction");
                    return;
                }
                tunnelDirection = next;
                tunnelDirectionLockTicks = TUNNEL_DIR_LOCK_TICKS;
                return;
            }
            if (isHazardous(context, frontFeet) || isHazardous(context, frontHead)) {
                player.sendMessage(Text.literal("[SpeedrunBot] Hazard in tunnel, returning"), true);
                transition(State.RETURN_TO_SHAFT, "hazard_in_tunnel");
                return;
            }
        }

        // Mine head first, then feet.
        if (!frontHeadAir) {
            if (wouldExposeFluid(context, frontHead)) {
                blockedFallbackUsed = true;
                transition(State.RETURN_TO_SHAFT, "fluid_risk_head");
                return;
            }
            if (mineBlock(context, frontHead, tunnelDirection.getOpposite(), false)) {
                announceCobbleProgress(player);
            }
            return;
        }
        if (!frontFeetAir) {
            if (wouldExposeFluid(context, frontFeet)) {
                blockedFallbackUsed = true;
                transition(State.RETURN_TO_SHAFT, "fluid_risk_feet");
                return;
            }
            if (mineBlock(context, frontFeet, tunnelDirection.getOpposite(), false)) {
                announceCobbleProgress(player);
            }
        }
    }

    private void tickReturnToShaft(BotContext context) {
        ClientPlayerEntity player = context.player();
        if (stateTicks > MAX_RETURN_TICKS) {
            transition(State.STAIRCASE_UP, "return_timeout");
            return;
        }

        BlockPos currentPos = player.getBlockPos();
        if (lastReturnPos != null && lastReturnPos.equals(currentPos)) {
            returnStuckTicks++;
        } else {
            returnStuckTicks = 0;
        }
        lastReturnPos = currentPos.toImmutable();

        // Return to shaft column's X,Z at current depth (not 3D distance to surface position).
        double dx = shaftTopPos.getX() + 0.5 - player.getX();
        double dz = shaftTopPos.getZ() + 0.5 - player.getZ();
        if (dx * dx + dz * dz <= 0.5 * 0.5) {
            transition(State.EQUIP_TOWER_BLOCK, "at_shaft_column");
            return;
        }

        if (returnStuckTicks > RETURN_STUCK_TICKS) {
            returnAltTicks++;
            if (returnAltTicks <= RETURN_ALT_TRY_TICKS) {
                context.actions().setBack(true);
                if ((ticks / 8) % 2 == 0) {
                    context.actions().setLeft(true);
                } else {
                    context.actions().setRight(true);
                }
                if (player.isOnGround() && ticks % 6 == 0) {
                    context.actions().setJump(true);
                }
                return;
            }

            transition(State.STAIRCASE_UP, "return_stuck_fallback");
            return;
        }

        Vec3d target = new Vec3d(shaftTopPos.getX() + 0.5, player.getY(), shaftTopPos.getZ() + 0.5);
        lookAt(player, target.add(0.0, 0.6, 0.0));
        moveToward(context, target, 0);
    }

    private void tickEquipTowerBlock(BotContext context) {
        if (!equipItem(context, TOWER_BLOCKS)) {
            context.player().sendMessage(Text.literal("[SpeedrunBot] No tower blocks, mining staircase up"), true);
            transition(State.STAIRCASE_UP, "no_tower_blocks");
            return;
        }

        actionCooldown = 2;
        transition(State.TOWER_UP, "tower_block_equipped");
    }

    private void tickTowerUp(BotContext context) {
        ClientPlayerEntity player = context.player();
        ClientPlayerInteractionManager interaction = context.client().interactionManager;
        if (interaction == null) {
            return;
        }

        if (!hasAnyTowerBlock(player)) {
            player.sendMessage(Text.literal("[SpeedrunBot] Out of tower blocks, mining staircase up"), true);
            transition(State.STAIRCASE_UP, "tower_blocks_exhausted");
            return;
        }

        if (isSurfaceCraftReady(context)) {
            player.sendMessage(Text.literal("[SpeedrunBot] Back near surface"), true);
            finish(context, "tower_surface_reached");
            transition(State.DONE, "tower_surface_reached");
            return;
        }

        BlockPos abovePos = player.getBlockPos().up(2);
        if (!context.world().getBlockState(abovePos).isAir()) {
            // Do not mine new blocks on return; this should be a clean shaft tower-up.
            player.sendMessage(Text.literal("[SpeedrunBot] Shaft blocked above, stopping"), true);
            transition(State.STAIRCASE_UP, "shaft_blocked");
            return;
        }

        BlockPos belowPos = player.getBlockPos().down();
        aimPitchDown(player);

        if (player.isOnGround()) {
            context.actions().setJump(true);
            context.actions().setSneak(true);
            return;
        }

        // Place while descending so the target is reliably below the player.
        if (player.getVelocity().y > -0.02) {
            context.actions().setSneak(true);
            return;
        }

        BlockHitResult hitResult = new BlockHitResult(Vec3d.ofCenter(belowPos), Direction.UP, belowPos, false);
        interaction.interactBlock(player, Hand.MAIN_HAND, hitResult);
        context.actions().setUse(true);
        context.actions().setSneak(true);
        actionCooldown = 1;
    }

    private void tickStaircaseUp(BotContext context) {
        ClientPlayerEntity player = context.player();
        if (stateTicks > MAX_STAIRCASE_TICKS) {
            transition(State.MINE_UP, "staircase_timeout");
            return;
        }

        if (isSurfaceCraftReady(context)) {
            player.sendMessage(Text.literal("[SpeedrunBot] Back near surface"), true);
            finish(context, "stair_surface_reached");
            transition(State.DONE, "stair_surface_reached");
            return;
        }

        if (player.getBlockY() > lastStairY) {
            stairNoProgressTicks = 0;
        } else {
            stairNoProgressTicks++;
        }
        lastStairY = player.getBlockY();

        if (stairNoProgressTicks > STAIRCASE_NO_PROGRESS_TICKS) {
            Direction next = pickStairDirection(context, player.getBlockPos(), rotateDirection(staircaseDirection));
            if (next != null) {
                staircaseDirection = next;
                stairNoProgressTicks = 0;
                stairDirectionFailCount++;
            } else {
                stairDirectionFailCount++;
            }

            if (stairDirectionFailCount >= MAX_STAIR_DIR_FAILS) {
                transition(State.MINE_UP, "stair_no_progress");
                return;
            }
        }

        BlockPos basePos = player.getBlockPos();
        if (!isValidStairDirection(context, basePos, staircaseDirection)) {
            Direction next = pickStairDirection(context, basePos, staircaseDirection);
            if (next == null) {
                stairDirectionFailCount++;
                if (stairDirectionFailCount >= MAX_STAIR_DIR_FAILS) {
                    player.sendMessage(Text.literal("[SpeedrunBot] Staircase blocked, mining upward"), true);
                    transition(State.MINE_UP, "no_stair_direction");
                }
                return;
            }
            staircaseDirection = next;
            stairNoProgressTicks = 0;
        }

        BlockPos stepBlock = basePos.offset(staircaseDirection);
        BlockPos clearHead = stepBlock.up();
        BlockPos clearTop = clearHead.up();

        if (!context.world().getBlockState(clearTop).isAir()) {
            mineStairBlock(context, clearTop, staircaseDirection.getOpposite());
            return;
        }

        if (!context.world().getBlockState(clearHead).isAir()) {
            mineStairBlock(context, clearHead, staircaseDirection.getOpposite());
            return;
        }

        Vec3d stepTopCenter = Vec3d.ofCenter(stepBlock).add(0.0, 1.0, 0.0);
        lookAt(player, stepTopCenter);
        context.actions().setForward(true);
        if (player.isOnGround()) {
            context.actions().setJump(true);
        }
    }

    private void tickMineUp(BotContext context) {
        ClientPlayerEntity player = context.player();
        if (stateTicks > MAX_MINE_UP_TICKS) {
            finish(context, "mine_up_timeout");
            transition(State.DONE, "mine_up_timeout");
            return;
        }

        if (isSurfaceCraftReady(context)) {
            finish(context, "mine_up_surface_reached");
            transition(State.DONE, "mine_up_surface_reached");
            return;
        }

        BlockPos above = player.getBlockPos().up();
        BlockState aboveState = context.world().getBlockState(above);
        if (!aboveState.isAir()) {
            if (wouldExposeFluid(context, above)) {
                blockedFallbackUsed = true;
                context.actions().setJump(true);
                return;
            }
            equipBestToolForBlock(context, aboveState);
            mineBlock(context, above, Direction.DOWN, false);
            return;
        }

        context.actions().setJump(true);
    }

    private boolean mineBlock(BotContext context, BlockPos target, Direction hitSide, boolean lookDown) {
        ClientPlayerInteractionManager interaction = context.client().interactionManager;
        if (interaction == null) {
            return false;
        }

        BlockState stateAtTarget = context.world().getBlockState(target);
        if (stateAtTarget.isAir()) {
            return false;
        }

        if (lookDown) {
            aimPitchDown(context.player());
        } else {
            lookAt(context.player(), Vec3d.ofCenter(target));
        }

        interaction.attackBlock(target, hitSide);
        interaction.updateBlockBreakingProgress(target, hitSide);
        context.player().swingHand(Hand.MAIN_HAND);
        context.actions().setAttack(true);
        return true;
    }

    private boolean mineStairBlock(BotContext context, BlockPos target, Direction hitSide) {
        BlockState state = context.world().getBlockState(target);
        if (state.isAir()) {
            return false;
        }

        if (wouldExposeFluid(context, target)) {
            return false;
        }

        equipBestToolForBlock(context, state);

        return mineBlock(context, target, hitSide, false);
    }

    private void equipBestToolForBlock(BotContext context, BlockState state) {
        if (state.isIn(BlockTags.LOGS) || state.isIn(BlockTags.AXE_MINEABLE)) {
            equipItem(context,
                Items.NETHERITE_AXE,
                Items.DIAMOND_AXE,
                Items.IRON_AXE,
                Items.STONE_AXE,
                Items.GOLDEN_AXE,
                Items.WOODEN_AXE
            );
            return;
        }

        if (state.isToolRequired() || state.isIn(BlockTags.PICKAXE_MINEABLE)) {
            equipItem(context,
                Items.NETHERITE_PICKAXE,
                Items.DIAMOND_PICKAXE,
                Items.IRON_PICKAXE,
                Items.STONE_PICKAXE,
                Items.GOLDEN_PICKAXE,
                Items.WOODEN_PICKAXE
            );
            return;
        }

        if (state.isIn(BlockTags.SHOVEL_MINEABLE)) {
            equipItem(context,
                Items.NETHERITE_SHOVEL,
                Items.DIAMOND_SHOVEL,
                Items.IRON_SHOVEL,
                Items.STONE_SHOVEL,
                Items.GOLDEN_SHOVEL,
                Items.WOODEN_SHOVEL
            );
            return;
        }

        if (state.isIn(BlockTags.HOE_MINEABLE)) {
            equipItem(context,
                Items.NETHERITE_HOE,
                Items.DIAMOND_HOE,
                Items.IRON_HOE,
                Items.STONE_HOE,
                Items.GOLDEN_HOE,
                Items.WOODEN_HOE
            );
            return;
        }

        equipFist(context.player());
    }

    private static boolean hasAnyTowerBlock(ClientPlayerEntity player) {
        for (int i = 0; i < player.getInventory().size(); i++) {
            ItemStack stack = player.getInventory().getStack(i);
            if (stack.isEmpty()) {
                continue;
            }
            for (Item tower : TOWER_BLOCKS) {
                if (stack.isOf(tower)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isValidStairDirection(BotContext context, BlockPos basePos, Direction direction) {
        if (direction == null || direction.getAxis().isVertical()) {
            return false;
        }
        BlockPos stepBlock = basePos.offset(direction);
        BlockState stepState = context.world().getBlockState(stepBlock);
        return !stepState.isAir() && stepState.getFluidState().isEmpty();
    }

    private static Direction pickStairDirection(BotContext context, BlockPos basePos, Direction preferred) {
        Direction[] dirs = new Direction[]{Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};

        if (isValidStairDirection(context, basePos, preferred)) {
            return preferred;
        }

        Direction best = null;
        int bestScore = Integer.MIN_VALUE;
        for (Direction dir : dirs) {
            if (!isValidStairDirection(context, basePos, dir)) {
                continue;
            }

            BlockPos stepBlock = basePos.offset(dir);
            int score = 0;
            if (context.world().getBlockState(stepBlock.up()).isAir()) {
                score += 2;
            }
            if (context.world().getBlockState(stepBlock.up(2)).isAir()) {
                score += 2;
            }
            if (score > bestScore) {
                bestScore = score;
                best = dir;
            }
        }

        return best;
    }

    private static void equipFist(ClientPlayerEntity player) {
        for (int slot = 0; slot < 9; slot++) {
            if (player.getInventory().getStack(slot).isEmpty()) {
                selectHotbarSlot(player, slot);
                return;
            }
        }
    }

    private boolean equipItem(BotContext context, Item... items) {
        ClientPlayerEntity player = context.player();
        ClientPlayerInteractionManager interaction = context.client().interactionManager;
        ScreenHandler handler = player.playerScreenHandler;
        if (interaction == null || handler == null) {
            return false;
        }

        int invIndex = findInvIndex(player, items);
        if (invIndex == -1) {
            return false;
        }

        if (invIndex >= 9) {
            interaction.clickSlot(handler.syncId, invIndex, 0, SlotActionType.SWAP, player);
            selectHotbarSlot(player, 0);
            return true;
        }

        if (invIndex != 0) {
            int hotbarScreenSlot = 36 + invIndex;
            interaction.clickSlot(handler.syncId, hotbarScreenSlot, 0, SlotActionType.SWAP, player);
        }

        selectHotbarSlot(player, 0);
        return true;
    }

    private void announceCobbleProgress(ClientPlayerEntity player) {
        if (ticks % 5 != 0) {
            return;
        }

        int current = countCobblestone(player);
        if (current > lastCobblestoneCount) {
            lastCobblestoneCount = current;
            player.sendMessage(Text.literal("[SpeedrunBot] Cobblestone: " + current + " / " + COBBLESTONE_TARGET), true);
        }
    }

    private int gainedCobblestone(ClientPlayerEntity player) {
        return countCobblestone(player) - startingCobblestone;
    }

    private static boolean isHazardous(BotContext context, BlockPos pos) {
        BlockState state = context.world().getBlockState(pos);
        return state.isOf(Blocks.LAVA) || state.isOf(Blocks.WATER) || state.isOf(Blocks.CAVE_AIR);
    }

    private static boolean wouldExposeFluid(BotContext context, BlockPos target) {
        for (Direction dir : Direction.values()) {
            BlockPos neighbor = target.offset(dir);
            BlockState neighborState = context.world().getBlockState(neighbor);
            if (!neighborState.getFluidState().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private static boolean isStoneLikeForTunnel(BlockState state) {
        if (state.isAir() || !state.getFluidState().isEmpty()) {
            return false;
        }
        return state.isIn(BlockTags.PICKAXE_MINEABLE);
    }

    private boolean isSurfaceCraftReady(BotContext context) {
        ClientPlayerEntity player = context.player();
        if (player.getBlockY() < startSurfaceY - SURFACE_MARGIN) {
            return false;
        }

        if (!player.isOnGround() || player.isTouchingWater()) {
            return false;
        }

        BlockPos feet = player.getBlockPos();
        BlockPos head = feet.up();
        BlockPos below = feet.down();

        if (!context.world().getBlockState(feet).isAir()) {
            return false;
        }

        if (!context.world().getBlockState(head).isAir()) {
            return false;
        }

        if (context.world().getBlockState(below).isAir()) {
            return false;
        }

        return hasNearbyCraftingPlacement(context, feet, 2);
    }

    private static boolean hasNearbyCraftingPlacement(BotContext context, BlockPos origin, int radius) {
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
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isStoneEnvironment(BotContext context, BlockPos basePos) {
        BlockPos below = basePos.down();
        for (int i = 0; i < 3; i++) {
            if (isStoneLikeForTunnel(context.world().getBlockState(below.down(i)))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isStoneTunnelPair(BotContext context, BlockPos feetPos, BlockPos headPos) {
        return isStoneLikeForTunnel(context.world().getBlockState(feetPos))
            && isStoneLikeForTunnel(context.world().getBlockState(headPos));
    }

    private static Direction pickStoneTunnelDirection(BotContext context, BlockPos basePos) {
        Direction best = null;
        int bestScore = -1;

        for (Direction dir : new Direction[]{Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST}) {
            int score = 0;
            for (int step = 1; step <= 8; step++) {
                BlockPos feet = basePos.offset(dir, step);
                BlockPos head = feet.up();
                if (!isStoneTunnelPair(context, feet, head)) {
                    break;
                }
                score += step <= 4 ? 2 : 1;
            }

            if (score > bestScore) {
                bestScore = score;
                best = dir;
            }
        }

        return bestScore >= 3 ? best : null;
    }

    private static Direction rotateDirection(Direction direction) {
        if (direction == null || direction.getAxis().isVertical()) {
            return Direction.NORTH;
        }
        return direction.rotateYClockwise();
    }

    private static int countCobblestone(ClientPlayerEntity player) {
        int total = 0;
        for (int i = 0; i < player.getInventory().size(); i++) {
            ItemStack stack = player.getInventory().getStack(i);
            if (stack.isOf(Items.COBBLESTONE) || stack.isOf(Items.COBBLED_DEEPSLATE)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private static int findInvIndex(ClientPlayerEntity player, Item... items) {
        for (int i = 0; i < player.getInventory().size(); i++) {
            ItemStack stack = player.getInventory().getStack(i);
            for (Item item : items) {
                if (stack.isOf(item)) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static void selectHotbarSlot(ClientPlayerEntity player, int slot) {
        player.getInventory().setSelectedSlot(slot);
        if (player.networkHandler != null) {
            player.networkHandler.sendPacket(new UpdateSelectedSlotC2SPacket(slot));
        }
    }

    private static void aimPitchDown(ClientPlayerEntity player) {
        float pitchStep = MathHelper.clamp(90.0F - player.getPitch(), -8.0F, 8.0F);
        player.setPitch(MathHelper.clamp(player.getPitch() + pitchStep, -90.0F, 90.0F));
    }

    private static void lookAt(ClientPlayerEntity player, Vec3d target) {
        double dx = target.x - player.getX();
        double dy = target.y - player.getEyeY();
        double dz = target.z - player.getZ();
        double horizontal = Math.sqrt(dx * dx + dz * dz);

        float desiredYaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float desiredPitch = (float) (-Math.toDegrees(Math.atan2(dy, horizontal)));

        float yawStep = MathHelper.clamp(MathHelper.wrapDegrees(desiredYaw - player.getYaw()), -8.0F, 8.0F);
        float pitchStep = MathHelper.clamp(desiredPitch - player.getPitch(), -8.0F, 8.0F);

        player.setYaw(player.getYaw() + yawStep);
        player.setPitch(MathHelper.clamp(player.getPitch() + pitchStep, -90.0F, 90.0F));
    }

    private static void moveToward(BotContext context, Vec3d target, int jumpPeriodTicks) {
        ClientPlayerEntity player = context.player();
        context.actions().setForward(true);

        // In water: only surface when air supply is running low (3 bubbles = 60 ticks).
        if (player.isTouchingWater() && player.getAir() <= 60) {
            context.actions().setJump(true);
        }

        double dx = target.x - player.getX();
        double dz = target.z - player.getZ();
        float desiredYaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float yawDelta = MathHelper.wrapDegrees(desiredYaw - player.getYaw());
        player.setYaw(player.getYaw() + MathHelper.clamp(yawDelta, -4.0F, 4.0F));
        float pitchMax = player.isTouchingWater() ? 0.0F : 90.0F;
        player.setPitch(MathHelper.clamp(player.getPitch(), -90.0F, pitchMax));

        if (yawDelta > 35.0F) {
            context.actions().setRight(true);
        } else if (yawDelta < -35.0F) {
            context.actions().setLeft(true);
        }

        double horizontalDistSq = dx * dx + dz * dz;
        if (player.isOnGround() && jumpPeriodTicks > 0 && horizontalDistSq > 1.2 * 1.2 && player.age % jumpPeriodTicks == 0) {
            context.actions().setJump(true);
        }
    }

    private boolean handleWaterEmergency(BotContext context) {
        ClientPlayerEntity player = context.player();
        if (!player.isTouchingWater()) {
            waterContactTicks = 0;
            waterEscapeAnnounced = false;
            return false;
        }

        waterContactTicks++;
        context.actions().setJump(true);

        if (waterContactTicks < WATER_CONTACT_ESCAPE_TICKS) {
            return false;
        }

        if (!waterEscapeAnnounced) {
            player.sendMessage(Text.literal("[SpeedrunBot] Water contact, switching to escape path"), true);
            waterEscapeAnnounced = true;
        }

        blockedFallbackUsed = true;
        if (state != State.STAIRCASE_UP && state != State.MINE_UP && state != State.DONE) {
            transition(State.STAIRCASE_UP, "water_escape_stair");
            return true;
        }

        if (state == State.STAIRCASE_UP && waterContactTicks > WATER_CONTACT_ESCAPE_TICKS + 20) {
            transition(State.MINE_UP, "water_escape_mine_up");
            return true;
        }

        return false;
    }

    private void transition(State next) {
        state = next;
        stateTicks = 0;
    }

    private void transition(State next, String reason) {
        state = next;
        stateTicks = 0;
        if (reason == null || reason.isEmpty()) {
            return;
        }
    }

    private void finish(BotContext context, String reason) {
        if (!"running".equals(exitReason)) {
            return;
        }

        exitReason = reason;
        emitSummary(context, "done");
    }

    private void emitSummary(BotContext context, String endType) {
        ClientPlayerEntity player = context.player();
        int gained = gainedCobblestone(player);
        int capped = Math.min(gained, BLOCKED_COBBLESTONE_TARGET_MAX);
        String line = "[SpeedrunBot] Stone summary (" + endType + ") gained=" + gained
            + " capped=" + capped
            + " target=" + COBBLESTONE_TARGET
            + " blockedFallback=" + blockedFallbackUsed
            + " exit=" + exitReason;
        player.sendMessage(Text.literal(line), false);
    }
}
