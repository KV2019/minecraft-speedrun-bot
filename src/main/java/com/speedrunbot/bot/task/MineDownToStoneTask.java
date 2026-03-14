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
        DONE
    }

    private static final int COBBLESTONE_TARGET = 16;
    private static final int INITIAL_STONE_TARGET = 4;
    private static final int MAX_DIG_DEPTH = 20;
    private static final int SURFACE_MARGIN = 1;
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
        context.player().sendMessage(Text.literal("[SpeedrunBot] Mining to stone, then tunneling"), true);
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
            case DIG_TO_STONE -> tickDigToStone(context);
            case MINE_TUNNEL -> tickMineTunnel(context);
            case RETURN_TO_SHAFT -> tickReturnToShaft(context);
            case EQUIP_TOWER_BLOCK -> tickEquipTowerBlock(context);
            case TOWER_UP -> tickTowerUp(context);
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
        state = State.DONE;
    }

    private void tickEquipPickaxe(BotContext context) {
        if (!equipItem(context, Items.WOODEN_PICKAXE, Items.STONE_PICKAXE)) {
            context.player().sendMessage(Text.literal("[SpeedrunBot] No pickaxe found"), true);
            transition(State.DONE);
            return;
        }

        actionCooldown = 3;
        transition(State.DIG_TO_STONE);
    }

    private void tickDigToStone(BotContext context) {
        ClientPlayerEntity player = context.player();
        if (player.getBlockY() < startSurfaceY - MAX_DIG_DEPTH) {
            player.sendMessage(Text.literal("[SpeedrunBot] Reached dig depth limit"), true);
            transition(State.EQUIP_TOWER_BLOCK);
            return;
        }

        BlockPos belowPos = player.getBlockPos().down();
        if (isHazardous(context, belowPos)) {
            player.sendMessage(Text.literal("[SpeedrunBot] Hazard below, returning"), true);
            transition(State.EQUIP_TOWER_BLOCK);
            return;
        }

        // Only try to transition once we have enough cobblestone AND a valid stone corridor.
        if (gainedCobblestone(player) >= INITIAL_STONE_TARGET) {
            Direction chosen = pickStoneTunnelDirection(context, player.getBlockPos());
            if (chosen != null) {
                tunnelDirection = chosen;
                player.sendMessage(Text.literal("[SpeedrunBot] Found stone, mining sideways"), true);
                transition(State.MINE_TUNNEL);
                return;
            }
            // No valid direction yet — keep digging deeper.
        }

        if (mineBlock(context, belowPos, Direction.UP, true)) {
            announceCobbleProgress(player);
        }
    }

    private void tickMineTunnel(BotContext context) {
        ClientPlayerEntity player = context.player();
        if (countCobblestone(player) >= COBBLESTONE_TARGET) {
            player.sendMessage(Text.literal("[SpeedrunBot] Collected enough stone, returning"), true);
            transition(State.RETURN_TO_SHAFT);
            return;
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
            if (!isStoneTunnelPair(context, frontFeet, frontHead)) {
                Direction next = pickStoneTunnelDirection(context, basePos);
                if (next == null) {
                    player.sendMessage(Text.literal("[SpeedrunBot] Tunnel left stone layer, returning"), true);
                    transition(State.RETURN_TO_SHAFT);
                    return;
                }
                tunnelDirection = next;
                return;
            }
            if (isHazardous(context, frontFeet) || isHazardous(context, frontHead)) {
                player.sendMessage(Text.literal("[SpeedrunBot] Hazard in tunnel, returning"), true);
                transition(State.RETURN_TO_SHAFT);
                return;
            }
        }

        // Mine head first, then feet.
        if (!frontHeadAir) {
            if (mineBlock(context, frontHead, tunnelDirection.getOpposite(), false)) {
                announceCobbleProgress(player);
            }
            return;
        }
        if (!frontFeetAir) {
            if (mineBlock(context, frontFeet, tunnelDirection.getOpposite(), false)) {
                announceCobbleProgress(player);
            }
        }
    }

    private void tickReturnToShaft(BotContext context) {
        ClientPlayerEntity player = context.player();
        // Return to shaft column's X,Z at current depth (not 3D distance to surface position).
        double dx = shaftTopPos.getX() + 0.5 - player.getX();
        double dz = shaftTopPos.getZ() + 0.5 - player.getZ();
        if (dx * dx + dz * dz <= 0.5 * 0.5) {
            transition(State.EQUIP_TOWER_BLOCK);
            return;
        }

        Vec3d target = new Vec3d(shaftTopPos.getX() + 0.5, player.getY(), shaftTopPos.getZ() + 0.5);
        lookAt(player, target.add(0.0, 0.6, 0.0));
        moveToward(context, target, 0);
    }

    private void tickEquipTowerBlock(BotContext context) {
        if (!equipItem(context, TOWER_BLOCKS)) {
            context.player().sendMessage(Text.literal("[SpeedrunBot] No tower blocks, stopping underground"), true);
            transition(State.DONE);
            return;
        }

        actionCooldown = 2;
        transition(State.TOWER_UP);
    }

    private void tickTowerUp(BotContext context) {
        ClientPlayerEntity player = context.player();
        ClientPlayerInteractionManager interaction = context.client().interactionManager;
        if (interaction == null) {
            return;
        }

        if (player.getBlockY() >= startSurfaceY - SURFACE_MARGIN) {
            player.sendMessage(Text.literal("[SpeedrunBot] Back near surface"), true);
            transition(State.DONE);
            return;
        }

        BlockPos abovePos = player.getBlockPos().up(2);
        if (!context.world().getBlockState(abovePos).isAir()) {
            // Do not mine new blocks on return; this should be a clean shaft tower-up.
            player.sendMessage(Text.literal("[SpeedrunBot] Shaft blocked above, stopping"), true);
            transition(State.DONE);
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

    private static boolean isStoneLikeForTunnel(BlockState state) {
        if (state.isAir() || !state.getFluidState().isEmpty()) {
            return false;
        }
        return state.isIn(BlockTags.PICKAXE_MINEABLE);
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
            for (int step = 1; step <= 5; step++) {
                BlockPos feet = basePos.offset(dir, step);
                BlockPos head = feet.up();
                if (!isStoneTunnelPair(context, feet, head)) {
                    break;
                }
                score++;
            }

            if (score > bestScore) {
                bestScore = score;
                best = dir;
            }
        }

        return bestScore > 0 ? best : null;
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

        double dx = target.x - player.getX();
        double dz = target.z - player.getZ();
        float desiredYaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float yawDelta = MathHelper.wrapDegrees(desiredYaw - player.getYaw());
        player.setYaw(player.getYaw() + MathHelper.clamp(yawDelta, -4.0F, 4.0F));

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

    private void transition(State next) {
        state = next;
    }
}
