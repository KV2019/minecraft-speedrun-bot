package com.speedrunbot.bot.task;

import com.speedrunbot.bot.BotContext;
import com.speedrunbot.bot.navigation.Navigator;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class AutoMineNearestLogTask implements BotTask {
    private static final Logger LOGGER = LoggerFactory.getLogger(AutoMineNearestLogTask.class);
    private static final boolean DEBUG_TELEMETRY = true;
    private static final int DEBUG_PRINT_INTERVAL_TICKS = 20;
    private static final boolean DEBUG_TO_ACTIONBAR = false;

    private static final int SEARCH_RADIUS_XZ = 10;
    private static final int SEARCH_RADIUS_Y = 8;
    private static final double MINE_RANGE_SQ = 4.5 * 4.5;
    private static final int SERVER_CONFIRM_TICKS = 30;
    private static final int POST_BREAK_RETARGET_COOLDOWN = 12;
    private static final int SAME_TARGET_STUCK_TICKS = 90;
    private static final int MAX_NO_SIGHT_TICKS = 36;
    private static final int MAX_OBSTRUCTED_TICKS = 36;
    private static final int CLEAR_AREA_FINISH_TICKS = 80;

    private BlockPos targetLog;
    private int ticks;
    private boolean completed;
    private int retargetCooldown;
    private int startingLogItemCount;
    private int targetAgeTicks;
    private int targetMissingTicks;
    private BlockPos activeMineTarget;
    private boolean awaitingServerConfirm;
    private int serverConfirmTicks;
    private BlockPos recentlyBrokenLog;
    private int sameTargetTicks;
    private int noSightTicks;
    private int obstructedTicks;
    private int noLogTicks;
    private int collectedLogs;

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
        targetAgeTicks = 0;
        targetMissingTicks = 0;
        activeMineTarget = null;
        awaitingServerConfirm = false;
        serverConfirmTicks = 0;
        recentlyBrokenLog = null;
        sameTargetTicks = 0;
        noSightTicks = 0;
        obstructedTicks = 0;
        noLogTicks = 0;
        collectedLogs = 0;
        context.navigator().reset();
        context.player().sendMessage(Text.literal("[SpeedrunBot] Searching for nearby logs"), true);
        debug(context, "start logs=" + startingLogItemCount);
    }

    @Override
    public void tick(BotContext context) {
        ticks++;

        int currentLogs = countLogItems(context.player());
        if (currentLogs > startingLogItemCount + collectedLogs) {
            int gained = currentLogs - (startingLogItemCount + collectedLogs);
            collectedLogs += gained;
            noLogTicks = 0;
            context.player().sendMessage(Text.literal("[SpeedrunBot] Collected log item"), true);
            debug(context, "collect gained=" + gained + " total_collected=" + collectedLogs);
        }

        if (awaitingServerConfirm) {
            if (recentlyBrokenLog != null && isLog(context, recentlyBrokenLog)) {
                // Server corrected the block state back to log; resume mining that target.
                awaitingServerConfirm = false;
                targetLog = recentlyBrokenLog.toImmutable();
                recentlyBrokenLog = null;
                serverConfirmTicks = 0;
                debug(context, "confirm_result rollback_to_log");
            } else {
                serverConfirmTicks--;
                if (serverConfirmTicks <= 0) {
                    awaitingServerConfirm = false;
                    recentlyBrokenLog = null;
                    targetLog = null;
                    activeMineTarget = null;
                    retargetCooldown = POST_BREAK_RETARGET_COOLDOWN;
                    noSightTicks = 0;
                    obstructedTicks = 0;
                    debug(context, "confirm_result timeout_reacquire");
                } else {
                    debug(context, "confirm_wait ticks_left=" + serverConfirmTicks);
                }
                return;
            }
        }

        if (retargetCooldown > 0) {
            retargetCooldown--;
        }
        context.navigator().tickCooldowns();

        if (targetLog != null && isLog(context, targetLog)) {
            targetMissingTicks = 0;
        } else if (targetLog != null) {
            // Keep the target for a short time to allow server corrections/drop pickup.
            targetMissingTicks++;
            if (targetMissingTicks < 20) {
                moveToward(context, Vec3d.ofCenter(targetLog), 14);
                debug(context, "target_missing grace=" + targetMissingTicks + " target=" + targetLog.toShortString());
                return;
            }

            targetLog = null;
            targetMissingTicks = 0;
            targetAgeTicks = 0;
            retargetCooldown = 10;
            context.navigator().reset();
            debug(context, "drop_target reason=missing_too_long");
        }

        if (targetLog == null && retargetCooldown == 0) {
            targetLog = findNearestLog(context);
            retargetCooldown = 20;
            targetAgeTicks = 0;
            sameTargetTicks = 0;
            noSightTicks = 0;
            obstructedTicks = 0;
            context.navigator().reset();

            if (targetLog != null) {
                noLogTicks = 0;
                context.player().sendMessage(
                    Text.literal("[SpeedrunBot] Target log at " + targetLog.toShortString()),
                    true
                );
                debug(context, "acquire_target pos=" + targetLog.toShortString());
            } else {
                noLogTicks++;
                debug(context, "acquire_target none");
            }
        }

        if (targetLog == null) {
            if (collectedLogs > 0 && noLogTicks >= CLEAR_AREA_FINISH_TICKS) {
                completed = true;
                debug(context, "finish reason=area_cleared collected=" + collectedLogs);
                return;
            }

            // Gentle roam so the bot can discover trees without standing still forever.
            moveToward(
                context,
                context.player().getRotationVec(1.0F).add(context.player().getX(), context.player().getY(), context.player().getZ()),
                24
            );
            context.navigator().reset();
            debug(context, "roam no_target cooldown=" + retargetCooldown);
            return;
        }

        targetAgeTicks++;
        if (targetAgeTicks > 20 * 12) {
            // Soft reset target if we spent too long on it; do not finish task.
            targetLog = null;
            targetAgeTicks = 0;
            targetMissingTicks = 0;
            retargetCooldown = 20;
            if (context.client().interactionManager != null) {
                context.client().interactionManager.cancelBlockBreaking();
            }
            activeMineTarget = null;
            sameTargetTicks = 0;
            noSightTicks = 0;
            obstructedTicks = 0;
            context.navigator().reset();
            debug(context, "drop_target reason=aged_out_keep_task_active");
            return;
        }

        Vec3d targetCenter = Vec3d.ofCenter(targetLog);
        lookAt(context.player(), targetCenter);

        double distanceSq = context.player().squaredDistanceTo(targetCenter.x, targetCenter.y, targetCenter.z);
        if (distanceSq > MINE_RANGE_SQ) {
            followPathTowardTarget(context, targetLog, targetCenter);
            noSightTicks = 0;
            obstructedTicks = 0;
            debug(context, "move_to_target dist=" + String.format("%.2f", Math.sqrt(distanceSq)) + " target=" + targetLog.toShortString());
            return;
        }

        context.navigator().reset();

        ClientPlayerInteractionManager interaction = context.client().interactionManager;
        if (interaction == null) {
            return;
        }

        BlockPos mineTarget = resolveMineTarget(context, targetLog);
        if (mineTarget == null) {
            // Path around obstacles by continuing to move and occasionally hop.
            interaction.cancelBlockBreaking();
            activeMineTarget = null;
            obstructedTicks++;
            moveToward(context, targetCenter, 14);
            if (obstructedTicks > MAX_OBSTRUCTED_TICKS) {
                targetLog = null;
                retargetCooldown = 8;
                obstructedTicks = 0;
                noSightTicks = 0;
                context.navigator().reset();
                debug(context, "drop_target reason=obstructed_timeout");
                return;
            }
            debug(context, "mine_target none (obstructed) target=" + targetLog.toShortString());
            return;
        }
        obstructedTicks = 0;

        if (!hasDirectLineOfSight(context, mineTarget)) {
            interaction.cancelBlockBreaking();
            activeMineTarget = null;
            noSightTicks++;
            moveToward(context, targetCenter, 14);
            if (noSightTicks > MAX_NO_SIGHT_TICKS) {
                targetLog = null;
                retargetCooldown = 8;
                noSightTicks = 0;
                obstructedTicks = 0;
                context.navigator().reset();
                debug(context, "drop_target reason=no_sight_timeout");
                return;
            }
            debug(context, "mine_target no_direct_sight target=" + mineTarget.toShortString());
            return;
        }
        noSightTicks = 0;

        if (activeMineTarget == null || !activeMineTarget.equals(mineTarget)) {
            interaction.cancelBlockBreaking();
            activeMineTarget = mineTarget.toImmutable();
            sameTargetTicks = 0;
        } else {
            sameTargetTicks++;
        }

        if (sameTargetTicks > SAME_TARGET_STUCK_TICKS) {
            interaction.cancelBlockBreaking();
            activeMineTarget = null;
            targetLog = null;
            retargetCooldown = 20;
            sameTargetTicks = 0;
            noSightTicks = 0;
            obstructedTicks = 0;
            context.navigator().reset();
            debug(context, "drop_target reason=stuck_same_target");
            return;
        }

        Vec3d mineCenter = Vec3d.ofCenter(mineTarget);
        lookAt(context.player(), mineCenter);
        Direction hitSide = sideClosestToPlayer(context.player(), mineCenter);
        interaction.attackBlock(mineTarget, hitSide);
        interaction.updateBlockBreakingProgress(mineTarget, hitSide);
        context.player().swingHand(Hand.MAIN_HAND);
        context.actions().setAttack(true);
        debug(context, "mine block=" + mineTarget.toShortString() + " hitSide=" + hitSide);

        // Do not complete on client-side prediction; keep going until we actually pick up a log.
        if (!isLog(context, targetLog)) {
            awaitingServerConfirm = true;
            serverConfirmTicks = SERVER_CONFIRM_TICKS;
            recentlyBrokenLog = targetLog.toImmutable();
            targetLog = null;
            retargetCooldown = POST_BREAK_RETARGET_COOLDOWN;
            targetAgeTicks = 0;
            targetMissingTicks = 0;
            activeMineTarget = null;
            sameTargetTicks = 0;
            noSightTicks = 0;
            obstructedTicks = 0;
            context.navigator().reset();
            debug(context, "target_log_not_present confirm_window_start");
        }
    }

    @Override
    public boolean isFinished(BotContext context) {
        return completed;
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
        targetAgeTicks = 0;
        targetMissingTicks = 0;
        activeMineTarget = null;
        awaitingServerConfirm = false;
        serverConfirmTicks = 0;
        recentlyBrokenLog = null;
        sameTargetTicks = 0;
        noSightTicks = 0;
        obstructedTicks = 0;
        noLogTicks = 0;
        collectedLogs = 0;
        context.navigator().reset();
        debug(context, "stop");
    }

    private void followPathTowardTarget(BotContext context, BlockPos targetLogPos, Vec3d fallbackTargetCenter) {
        BlockPos goal = chooseGoalNearTarget(context, targetLogPos);

        if (goal == null) {
            moveToward(context, fallbackTargetCenter, 12);
            return;
        }
        context.navigator().navigateTo(context, goal, fallbackTargetCenter, 10);
    }

    private static BlockPos chooseGoalNearTarget(BotContext context, BlockPos targetLogPos) {
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        BlockPos playerPos = context.player().getBlockPos();

        for (Direction dir : Direction.Type.HORIZONTAL) {
            BlockPos candidate = targetLogPos.offset(dir);
            if (!Navigator.canStandAt(context, candidate)) {
                continue;
            }

            double score = candidate.getSquaredDistance(playerPos);
            if (score < bestScore) {
                bestScore = score;
                best = candidate.toImmutable();
            }
        }

        if (best != null) {
            return best;
        }

        // Fallback to player's current position if no standable adjacent goal is available.
        return playerPos;
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

                    // Prefer trunk-level logs over canopy logs to reduce erratic retargeting.
                    double distSq = pos.getSquaredDistance(origin);
                    double verticalPenalty = Math.abs(pos.getY() - origin.getY()) * 2.5;
                    if (pos.getY() > origin.getY() + 2) {
                        verticalPenalty += 8.0;
                    }

                    double score = distSq + verticalPenalty;
                    if (score < bestDistSq) {
                        bestDistSq = score;
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

        // If aiming near a block edge, try sampling log face points before giving up.
        if (canRayHitLog(context, from, intendedLog)) {
            return intendedLog;
        }

        BlockState hitState = context.world().getBlockState(hitPos);
        if (hitState.isIn(BlockTags.LEAVES)) {
            return hitPos.toImmutable();
        }

        return null;
    }

    private static boolean hasDirectLineOfSight(BotContext context, BlockPos target) {
        Vec3d from = context.player().getEyePos();
        Vec3d to = Vec3d.ofCenter(target);

        BlockHitResult hit = context.world().raycast(new RaycastContext(
            from,
            to,
            RaycastContext.ShapeType.COLLIDER,
            RaycastContext.FluidHandling.NONE,
            context.player()
        ));

        return hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(target);
    }

    private static boolean canRayHitLog(BotContext context, Vec3d from, BlockPos target) {
        Vec3d center = Vec3d.ofCenter(target);
        if (rayHits(context, from, center, target)) {
            return true;
        }

        for (Direction dir : Direction.values()) {
            Vec3d offset = new Vec3d(dir.getOffsetX(), dir.getOffsetY(), dir.getOffsetZ()).multiply(0.42);
            if (rayHits(context, from, center.add(offset), target)) {
                return true;
            }
        }

        return false;
    }

    private static boolean rayHits(BotContext context, Vec3d from, Vec3d to, BlockPos expectedTarget) {
        BlockHitResult hit = context.world().raycast(new RaycastContext(
            from,
            to,
            RaycastContext.ShapeType.COLLIDER,
            RaycastContext.FluidHandling.NONE,
            context.player()
        ));

        return hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(expectedTarget);
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

    private static void moveToward(BotContext context, Vec3d target, int jumpPeriodTicks) {
        ClientPlayerEntity player = context.player();
        context.actions().setForward(true);
        context.actions().setSprint(true);

        // Add simple steering so the bot can path around trunks/leaves without hard retarget hops.
        double dx = target.x - player.getX();
        double dz = target.z - player.getZ();
        float desiredYaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float yawDelta = MathHelper.wrapDegrees(desiredYaw - player.getYaw());
        player.setYaw(player.getYaw() + MathHelper.clamp(yawDelta, -3.0F, 3.0F));

        // Avoid camera lock looking straight down while navigating.
        player.setPitch(MathHelper.clamp(player.getPitch(), -25.0F, 25.0F));

        if (yawDelta > 35.0F) {
            context.actions().setRight(true);
        } else if (yawDelta < -35.0F) {
            context.actions().setLeft(true);
        }

        if (player.isOnGround() && jumpPeriodTicks > 0 && player.age % jumpPeriodTicks == 0) {
            context.actions().setJump(true);
        }
    }

    private void debug(BotContext context, String message) {
        if (!DEBUG_TELEMETRY) {
            return;
        }

        if (ticks % DEBUG_PRINT_INTERVAL_TICKS != 0 &&
            !message.startsWith("start") &&
            !message.startsWith("finish") &&
            !message.startsWith("stop") &&
            !message.startsWith("acquire_target") &&
            !message.startsWith("drop_target")) {
            return;
        }

        String target = targetLog == null ? "none" : targetLog.toShortString();
        int logs = countLogItems(context.player());
        String line = "[SBOT] " + message +
            " | t=" + ticks +
            " target=" + target +
            " age=" + targetAgeTicks +
            " miss=" + targetMissingTicks +
            " logs=" + logs;

        LOGGER.info(line);
        if (DEBUG_TO_ACTIONBAR) {
            context.player().sendMessage(Text.literal(line), true);
        }
    }

    private static void lookAt(ClientPlayerEntity player, Vec3d target) {
        double dx = target.x - player.getX();
        double dy = target.y - player.getEyeY();
        double dz = target.z - player.getZ();
        double horizontal = Math.sqrt(dx * dx + dz * dz);

        float desiredYaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float desiredPitch = (float) (-Math.toDegrees(Math.atan2(dy, horizontal)));

        float yawStep = MathHelper.clamp(MathHelper.wrapDegrees(desiredYaw - player.getYaw()), -8.0F, 8.0F);
        float pitchStep = MathHelper.clamp(desiredPitch - player.getPitch(), -6.0F, 6.0F);

        player.setYaw(player.getYaw() + yawStep);
        player.setPitch(MathHelper.clamp(player.getPitch() + pitchStep, -55.0F, 55.0F));
    }

    private static Direction sideClosestToPlayer(ClientPlayerEntity player, Vec3d blockCenter) {
        double dx = player.getX() - blockCenter.x;
        double dy = player.getEyeY() - blockCenter.y;
        double dz = player.getZ() - blockCenter.z;
        return Direction.getFacing(dx, dy, dz);
    }
}