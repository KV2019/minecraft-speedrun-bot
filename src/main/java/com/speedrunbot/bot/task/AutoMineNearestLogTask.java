package com.speedrunbot.bot.task;

import com.speedrunbot.bot.BotContext;
import net.minecraft.block.BlockState;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

public final class AutoMineNearestLogTask implements BotTask {
    private static final int SEARCH_RADIUS_XZ = 8;
    private static final int SEARCH_RADIUS_Y = 4;
    private static final double MINE_RANGE_SQ = 4.5 * 4.5;
    private static final int MAX_TICKS = 20 * 35;

    private BlockPos targetLog;
    private int ticks;
    private boolean completed;
    private int retargetCooldown;
    private int startingLogItemCount;

    @Override
    public String name() {
        return "Mine Nearest Log";
    }

    @Override
    public void start(BotContext context) {
        targetLog = null;
        ticks = 0;
        completed = false;
        retargetCooldown = 0;
        startingLogItemCount = countLogItems(context.player());
        context.player().sendMessage(Text.literal("[SpeedrunBot] Searching for nearby logs"), true);
    }

    @Override
    public void tick(BotContext context) {
        ticks++;

        if (countLogItems(context.player()) > startingLogItemCount) {
            completed = true;
            context.player().sendMessage(Text.literal("[SpeedrunBot] Collected log item"), true);
            return;
        }

        if (retargetCooldown > 0) {
            retargetCooldown--;
        }

        if (targetLog == null || !isLog(context, targetLog)) {
            targetLog = findNearestLog(context);
            retargetCooldown = 10;

            if (targetLog != null) {
                context.player().sendMessage(
                    Text.literal("[SpeedrunBot] Target log at " + targetLog.toShortString()),
                    true
                );
            }
        }

        if (targetLog == null) {
            // Gentle roam so the bot can discover trees without standing still forever.
            context.actions().setForward(true);
            context.actions().setSprint(true);
            if (context.player().isOnGround() && ticks % 24 == 0) {
                context.actions().setJump(true);
            }
            return;
        }

        Vec3d targetCenter = Vec3d.ofCenter(targetLog);
        lookAt(context.player(), targetCenter);

        double distanceSq = context.player().squaredDistanceTo(targetCenter.x, targetCenter.y, targetCenter.z);
        if (distanceSq > MINE_RANGE_SQ) {
            context.actions().setForward(true);
            context.actions().setSprint(true);

            if (context.player().isOnGround() && ticks % 12 == 0) {
                context.actions().setJump(true);
            }
            return;
        }

        ClientPlayerInteractionManager interaction = context.client().interactionManager;
        if (interaction == null) {
            return;
        }

        BlockPos mineTarget = resolveMineTarget(context, targetLog);
        if (mineTarget == null) {
            // Path around obstacles by continuing to move and occasionally hop.
            context.actions().setForward(true);
            context.actions().setSprint(true);
            if (context.player().isOnGround() && ticks % 14 == 0) {
                context.actions().setJump(true);
            }
            return;
        }

        Vec3d mineCenter = Vec3d.ofCenter(mineTarget);
        lookAt(context.player(), mineCenter);
        Direction hitSide = sideClosestToPlayer(context.player(), mineCenter);
        interaction.attackBlock(mineTarget, hitSide);
        interaction.updateBlockBreakingProgress(mineTarget, hitSide);
        context.player().swingHand(Hand.MAIN_HAND);
        context.actions().setAttack(true);

        // Do not complete on client-side prediction; keep going until we actually pick up a log.
        if (!isLog(context, targetLog)) {
            targetLog = null;
            retargetCooldown = 0;
        }
    }

    @Override
    public boolean isFinished(BotContext context) {
        return completed || ticks >= MAX_TICKS;
    }

    @Override
    public void stop(BotContext context) {
        ClientPlayerInteractionManager interaction = context.client().interactionManager;
        if (interaction != null) {
            interaction.cancelBlockBreaking();
        }

        targetLog = null;
        ticks = 0;
        completed = false;
        retargetCooldown = 0;
        startingLogItemCount = 0;
    }

    private static BlockPos findNearestLog(BotContext context) {
        BlockPos origin = context.player().getBlockPos();
        BlockPos best = null;
        double bestDistSq = Double.MAX_VALUE;

        for (int dx = -SEARCH_RADIUS_XZ; dx <= SEARCH_RADIUS_XZ; dx++) {
            for (int dz = -SEARCH_RADIUS_XZ; dz <= SEARCH_RADIUS_XZ; dz++) {
                for (int dy = -SEARCH_RADIUS_Y; dy <= SEARCH_RADIUS_Y; dy++) {
                    BlockPos pos = origin.add(dx, dy, dz);
                    BlockState state = context.world().getBlockState(pos);
                    if (!state.isIn(BlockTags.LOGS)) {
                        continue;
                    }

                    if (!hasExposedFace(context, pos)) {
                        continue;
                    }

                    double distSq = pos.getSquaredDistance(origin);
                    if (distSq < bestDistSq) {
                        bestDistSq = distSq;
                        best = pos.toImmutable();
                    }
                }
            }
        }

        return best;
    }

    private static boolean isLog(BotContext context, BlockPos pos) {
        return context.world().getBlockState(pos).isIn(BlockTags.LOGS);
    }

    private static boolean hasExposedFace(BotContext context, BlockPos pos) {
        for (Direction direction : Direction.values()) {
            BlockState neighbor = context.world().getBlockState(pos.offset(direction));
            if (!neighbor.isIn(BlockTags.LOGS)) {
                return true;
            }
        }
        return false;
    }

    private static BlockPos resolveMineTarget(BotContext context, BlockPos intendedLog) {
        Vec3d from = context.player().getEyePos();
        Vec3d to = Vec3d.ofCenter(intendedLog);

        BlockHitResult hit = context.world().raycast(new RaycastContext(
            from,
            to,
            RaycastContext.ShapeType.COLLIDER,
            RaycastContext.FluidHandling.NONE,
            context.player()
        ));

        if (hit.getType() != HitResult.Type.BLOCK) {
            return null;
        }

        BlockPos hitPos = hit.getBlockPos();
        if (hitPos.equals(intendedLog)) {
            return intendedLog;
        }

        BlockState hitState = context.world().getBlockState(hitPos);
        if (hitState.isIn(BlockTags.LEAVES)) {
            return hitPos.toImmutable();
        }

        return null;
    }

    private static int countLogItems(ClientPlayerEntity player) {
        int total = 0;
        for (int i = 0; i < player.getInventory().size(); i++) {
            var stack = player.getInventory().getStack(i);
            if (stack.isIn(ItemTags.LOGS)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private static void lookAt(ClientPlayerEntity player, Vec3d target) {
        double dx = target.x - player.getX();
        double dy = target.y - player.getEyeY();
        double dz = target.z - player.getZ();
        double horizontal = Math.sqrt(dx * dx + dz * dz);

        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float pitch = (float) (-Math.toDegrees(Math.atan2(dy, horizontal)));

        player.setYaw(yaw);
        player.setPitch(MathHelper.clamp(pitch, -89.0F, 89.0F));
    }

    private static Direction sideClosestToPlayer(ClientPlayerEntity player, Vec3d blockCenter) {
        double dx = player.getX() - blockCenter.x;
        double dy = player.getEyeY() - blockCenter.y;
        double dz = player.getZ() - blockCenter.z;
        return Direction.getFacing(dx, dy, dz);
    }
}