package com.speedrunbot.bot.task;

import com.speedrunbot.bot.BotContext;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;
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
    private enum MinerState {
        ACQUIRE_TREE,
        APPROACH_TARGET,
        MINE_TARGET,
        COLLECT_DROPS,
        CLEAR_OBSTACLE,
        RECOVER,
        DONE
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(AutoMineNearestLogTask.class);
    private static final boolean DEBUG_TELEMETRY = false;
    private static final int DEBUG_PRINT_INTERVAL_TICKS = 20;
    private static final boolean DEBUG_TO_ACTIONBAR = false;

    private static final int SEARCH_RADIUS_XZ = 10;
    private static final int SEARCH_RADIUS_Y = 8;
    private static final double MINE_RANGE_SQ = 4.5 * 4.5;
    private static final int MAX_TICKS = 20 * 180;
    private static final int POST_BREAK_RETARGET_COOLDOWN = 12;
    private static final int SAME_TARGET_STUCK_TICKS = 80;
    private static final int MAX_NO_SIGHT_TICKS = 30;
    private static final int MAX_OBSTRUCTED_TICKS = 30;
    private static final int MAX_OBSTACLE_CLEAR_TICKS = 40;
    private static final int CLEAR_AREA_FINISH_TICKS = 80;
    private static final int MAX_CLUSTER_SIZE = 192;
    private static final int TARGET_AVOID_TICKS = 100;
    private static final int DROP_COLLECTION_TICKS = 24;

    private MinerState state;
    private BlockPos targetLog;
    private final Set<BlockPos> currentCluster = new HashSet<>();
    private int ticks;
    private int stateTicks;
    private boolean completed;
    private int retargetCooldown;
    private int startingLogItemCount;
    private int collectedLogs;
    private int noLogTicks;
    private int targetMissingTicks;
    private int sameTargetTicks;
    private int noSightTicks;
    private int obstructedTicks;
    private boolean awaitingServerConfirm;
    private BlockPos recentlyBrokenLog;
    private Vec3d lastProgressPos;
    private int noMoveTicks;
    private BlockPos obstacleBlock;
    private int obstacleClearTicks;
    private BlockPos avoidTarget;
    private int avoidTargetUntilTick;
    private BlockPos dropCollectPos;
    private int dropCollectTicks;
    private int dropCollectStartLogCount;

    @Override
    public String name() {
        return "Mine Nearest Log";
    }

    @Override
    public void start(BotContext context) {
        state = MinerState.ACQUIRE_TREE;
        targetLog = null;
        currentCluster.clear();
        ticks = 0;
        stateTicks = 0;
        completed = false;
        retargetCooldown = 0;
        startingLogItemCount = countLogItems(context.player());
        collectedLogs = 0;
        noLogTicks = 0;
        targetMissingTicks = 0;
        sameTargetTicks = 0;
        noSightTicks = 0;
        obstructedTicks = 0;
        awaitingServerConfirm = false;
        recentlyBrokenLog = null;
        lastProgressPos = null;
        noMoveTicks = 0;
        obstacleBlock = null;
        obstacleClearTicks = 0;
        avoidTarget = null;
        avoidTargetUntilTick = 0;
        dropCollectPos = null;
        dropCollectTicks = 0;
        dropCollectStartLogCount = 0;
        context.player().sendMessage(Text.literal("[SpeedrunBot] Searching for nearby logs"), true);
        debug(context, "start logs=" + startingLogItemCount);
    }

    @Override
    public void tick(BotContext context) {
        ticks++;
        stateTicks++;

        int nowLogs = countLogItems(context.player());
        if (nowLogs > startingLogItemCount + collectedLogs) {
            int gained = nowLogs - (startingLogItemCount + collectedLogs);
            collectedLogs += gained;
            noLogTicks = 0;
            context.player().sendMessage(Text.literal("[SpeedrunBot] Collected log item"), true);
            debug(context, "collect gained=" + gained + " total=" + collectedLogs);
        }

        if (awaitingServerConfirm) {
            if (recentlyBrokenLog != null && isLog(context, recentlyBrokenLog)) {
                awaitingServerConfirm = false;
                targetLog = recentlyBrokenLog.toImmutable();
                recentlyBrokenLog = null;
                transition(context, MinerState.MINE_TARGET, "confirm rollback_to_log");
            } else {
                awaitingServerConfirm = false;
                targetLog = null;
                retargetCooldown = 0;
                noSightTicks = 0;
                obstructedTicks = 0;
                dropCollectPos = recentlyBrokenLog == null ? null : recentlyBrokenLog.toImmutable();
                recentlyBrokenLog = null;
                dropCollectTicks = DROP_COLLECTION_TICKS;
                dropCollectStartLogCount = countLogItems(context.player());
                transition(context, MinerState.COLLECT_DROPS, "break_confirmed_collect");
                return;
            }
        }

        if (retargetCooldown > 0) {
            retargetCooldown--;
        }

        switch (state) {
            case ACQUIRE_TREE -> tickAcquireTree(context);
            case APPROACH_TARGET -> tickApproachTarget(context);
            case MINE_TARGET -> tickMineTarget(context);
            case COLLECT_DROPS -> tickCollectDrops(context);
            case CLEAR_OBSTACLE -> tickClearObstacle(context);
            case RECOVER -> tickRecover(context);
            case DONE -> completed = true;
        }
    }

    private void tickCollectDrops(BotContext context) {
        if (dropCollectPos == null) {
            transition(context, MinerState.ACQUIRE_TREE, "collect_no_pos");
            return;
        }

        int nowLogs = countLogItems(context.player());
        if (nowLogs > dropCollectStartLogCount) {
            dropCollectPos = null;
            dropCollectTicks = 0;
            transition(context, MinerState.ACQUIRE_TREE, "collect_got_drop");
            return;
        }

        if (dropCollectTicks <= 0) {
            dropCollectPos = null;
            transition(context, MinerState.ACQUIRE_TREE, "collect_timeout");
            return;
        }

        dropCollectTicks--;
        Vec3d targetCenter = Vec3d.ofCenter(dropCollectPos);
        lookAt(context.player(), targetCenter);
        moveToward(context, targetCenter, 8);
    }

    private void tickAcquireTree(BotContext context) {
        if (retargetCooldown > 0) {
            return;
        }

        pruneCluster(context);
        if (currentCluster.isEmpty()) {
            BlockPos seed = findNearestLog(context);
            if (seed == null) {
                noLogTicks++;
                if (collectedLogs > 0 && noLogTicks >= CLEAR_AREA_FINISH_TICKS) {
                    transition(context, MinerState.DONE, "area_cleared collected=" + collectedLogs);
                    return;
                }

                // Controlled exploration while searching: move several blocks ahead and hop obstacles.
                Vec3d exploreTarget = context.player()
                    .getRotationVec(1.0F)
                    .multiply(6.0)
                    .add(context.player().getX(), context.player().getY(), context.player().getZ());
                moveToward(
                    context,
                    exploreTarget,
                    8
                );
                debug(context, "acquire none noLogTicks=" + noLogTicks);
                return;
            }

            buildLogCluster(context, seed);
            noLogTicks = 0;
            debug(context, "cluster size=" + currentCluster.size() + " seed=" + seed.toShortString());
        }

        targetLog = pickClusterTarget(context);
        if (targetLog == null) {
            currentCluster.clear();
            retargetCooldown = 8;
            debug(context, "acquire cluster_no_target");
            return;
        }

        targetMissingTicks = 0;
        sameTargetTicks = 0;
        noSightTicks = 0;
        obstructedTicks = 0;
        lastProgressPos = new Vec3d(context.player().getX(), context.player().getY(), context.player().getZ());
        noMoveTicks = 0;
        context.player().sendMessage(Text.literal("[SpeedrunBot] Target log at " + targetLog.toShortString()), true);
        transition(context, MinerState.APPROACH_TARGET, "target=" + targetLog.toShortString());
    }

    private void tickApproachTarget(BotContext context) {
        if (targetLog == null || !isLog(context, targetLog)) {
            removeFromCluster(targetLog);
            targetLog = null;
            transition(context, MinerState.ACQUIRE_TREE, "target_missing_during_approach");
            return;
        }

        Vec3d targetCenter = Vec3d.ofCenter(targetLog);
        lookAt(context.player(), targetCenter);
        double distanceSq = context.player().squaredDistanceTo(targetCenter.x, targetCenter.y, targetCenter.z);
        if (distanceSq > MINE_RANGE_SQ) {
            moveToward(context, targetCenter, 12);
            updateMovementProgress(context);
            if (noMoveTicks > 30) {
                transition(context, MinerState.RECOVER, "approach_no_progress");
                return;
            }
            return;
        }

        transition(context, MinerState.MINE_TARGET, "in_range");
    }

    private void tickMineTarget(BotContext context) {
        if (targetLog == null || !isLog(context, targetLog)) {
            removeFromCluster(targetLog);
            targetLog = null;
            transition(context, MinerState.ACQUIRE_TREE, "target_missing_during_mine");
            return;
        }

        ClientPlayerInteractionManager interaction = context.client().interactionManager;
        if (interaction == null) {
            return;
        }

        Vec3d targetCenter = Vec3d.ofCenter(targetLog);
        double distanceSq = context.player().squaredDistanceTo(targetCenter.x, targetCenter.y, targetCenter.z);
        if (distanceSq > MINE_RANGE_SQ) {
            transition(context, MinerState.APPROACH_TARGET, "out_of_range");
            return;
        }

        BlockPos mineTarget = resolveMineTarget(context, targetLog);
        if (mineTarget == null) {
            BlockPos obstruction = detectObstacleBlock(context, targetLog);
            if (isClearableObstacle(context, obstruction)) {
                obstacleBlock = obstruction.toImmutable();
                obstacleClearTicks = 0;
                transition(context, MinerState.CLEAR_OBSTACLE, "clearable_obstacle=" + obstacleBlock.toShortString());
                return;
            }

            obstructedTicks++;
            moveToward(context, targetCenter, 14);
            if (obstructedTicks > MAX_OBSTRUCTED_TICKS) {
                startRecoverWithAvoid(context, "obstructed_timeout", true);
            }
            return;
        }
        obstructedTicks = 0;

        if (!hasDirectLineOfSight(context, mineTarget)) {
            BlockPos obstruction = detectObstacleBlock(context, targetLog);
            if (isClearableObstacle(context, obstruction)) {
                obstacleBlock = obstruction.toImmutable();
                obstacleClearTicks = 0;
                transition(context, MinerState.CLEAR_OBSTACLE, "no_los_clearable=" + obstacleBlock.toShortString());
                return;
            }

            noSightTicks++;
            moveToward(context, targetCenter, 14);
            if (noSightTicks > MAX_NO_SIGHT_TICKS) {
                startRecoverWithAvoid(context, "no_los_timeout", true);
            }
            return;
        }
        noSightTicks = 0;

        Vec3d mineCenter = Vec3d.ofCenter(mineTarget);
        lookAt(context.player(), mineCenter);
        Direction hitSide = sideClosestToPlayer(context.player(), mineCenter);
        interaction.attackBlock(mineTarget, hitSide);
        interaction.updateBlockBreakingProgress(mineTarget, hitSide);
        context.player().swingHand(Hand.MAIN_HAND);
        context.actions().setAttack(true);

        if (mineTarget.equals(targetLog)) {
            sameTargetTicks++;
            if (sameTargetTicks > SAME_TARGET_STUCK_TICKS) {
                startRecoverWithAvoid(context, "same_target_stuck", false);
                return;
            }
        } else {
            sameTargetTicks = 0;
        }

        if (!isLog(context, targetLog)) {
            awaitingServerConfirm = true;
            recentlyBrokenLog = targetLog.toImmutable();
            removeFromCluster(targetLog);
            targetLog = null;
            retargetCooldown = POST_BREAK_RETARGET_COOLDOWN;
            targetMissingTicks = 0;
            sameTargetTicks = 0;
            noSightTicks = 0;
            obstructedTicks = 0;
            transition(context, MinerState.ACQUIRE_TREE, "verify_break_start");
        }
    }

    private void tickClearObstacle(BotContext context) {
        if (obstacleBlock == null) {
            startRecoverWithAvoid(context, "missing_obstacle_target", true);
            return;
        }

        ClientPlayerInteractionManager interaction = context.client().interactionManager;
        if (interaction == null) {
            return;
        }

        if (!isClearableObstacle(context, obstacleBlock)) {
            obstacleBlock = null;
            obstacleClearTicks = 0;
            transition(context, MinerState.MINE_TARGET, "obstacle_already_gone");
            return;
        }

        obstacleClearTicks++;
        Vec3d center = Vec3d.ofCenter(obstacleBlock);
        lookAt(context.player(), center);

        if (!hasDirectLineOfSight(context, obstacleBlock)) {
            moveToward(context, center, 12);
            if (obstacleClearTicks > MAX_OBSTACLE_CLEAR_TICKS) {
                startRecoverWithAvoid(context, "obstacle_no_los_timeout", true);
            }
            return;
        }

        Direction hitSide = sideClosestToPlayer(context.player(), center);
        interaction.attackBlock(obstacleBlock, hitSide);
        interaction.updateBlockBreakingProgress(obstacleBlock, hitSide);
        context.player().swingHand(Hand.MAIN_HAND);
        context.actions().setAttack(true);

        if (!isClearableObstacle(context, obstacleBlock)) {
            obstacleBlock = null;
            obstacleClearTicks = 0;
            noSightTicks = 0;
            obstructedTicks = 0;
            transition(context, MinerState.MINE_TARGET, "obstacle_cleared");
            return;
        }

        if (obstacleClearTicks > MAX_OBSTACLE_CLEAR_TICKS) {
            startRecoverWithAvoid(context, "obstacle_clear_timeout", true);
        }
    }

    private void tickRecover(BotContext context) {
        ClientPlayerInteractionManager interaction = context.client().interactionManager;
        if (interaction != null) {
            interaction.cancelBlockBreaking();
        }

        // Simple recovery ladder: brief backoff + strafe, then reacquire target.
        if (stateTicks <= 12) {
            context.actions().setBack(true);
            if ((ticks / 6) % 2 == 0) {
                context.actions().setLeft(true);
            } else {
                context.actions().setRight(true);
            }
            if (context.player().isOnGround() && ticks % 6 == 0) {
                context.actions().setJump(true);
            }
            return;
        }

        targetLog = null;
        targetMissingTicks = 0;
        sameTargetTicks = 0;
        noSightTicks = 0;
        obstructedTicks = 0;
        noMoveTicks = 0;
        obstacleBlock = null;
        obstacleClearTicks = 0;
        retargetCooldown = 8;
        dropCollectPos = null;
        dropCollectTicks = 0;
        transition(context, MinerState.ACQUIRE_TREE, "recover_complete");
    }

    private void startRecoverWithAvoid(BotContext context, String reason, boolean avoidCurrentTarget) {
        if (avoidCurrentTarget && targetLog != null) {
            avoidTarget = targetLog.toImmutable();
            avoidTargetUntilTick = ticks + TARGET_AVOID_TICKS;
            debug(context, "avoid target=" + avoidTarget.toShortString() + " until=" + avoidTargetUntilTick + " reason=" + reason);
        }
        transition(context, MinerState.RECOVER, reason);
    }

    @Override
    public boolean isFinished(BotContext context) {
        if (ticks >= MAX_TICKS && !completed) {
            debug(context, "finish reason=timeout");
        }
        return completed || ticks >= MAX_TICKS;
    }

    @Override
    public void stop(BotContext context) {
        ClientPlayerInteractionManager interaction = context.client().interactionManager;
        if (interaction != null) {
            interaction.cancelBlockBreaking();
        }

        targetLog = null;
        currentCluster.clear();
        state = MinerState.ACQUIRE_TREE;
        ticks = 0;
        stateTicks = 0;
        completed = false;
        retargetCooldown = 0;
        startingLogItemCount = 0;
        collectedLogs = 0;
        noLogTicks = 0;
        targetMissingTicks = 0;
        sameTargetTicks = 0;
        noSightTicks = 0;
        obstructedTicks = 0;
        awaitingServerConfirm = false;
        recentlyBrokenLog = null;
        lastProgressPos = null;
        noMoveTicks = 0;
        obstacleBlock = null;
        obstacleClearTicks = 0;
        avoidTarget = null;
        avoidTargetUntilTick = 0;
        dropCollectPos = null;
        dropCollectTicks = 0;
        dropCollectStartLogCount = 0;
        debug(context, "stop");
    }

    private void transition(BotContext context, MinerState next, String reason) {
        if (state != next) {
            debug(context, "state " + state + " -> " + next + " reason=" + reason);
        }
        state = next;
        stateTicks = 0;
    }

    private void updateMovementProgress(BotContext context) {
        Vec3d current = new Vec3d(context.player().getX(), context.player().getY(), context.player().getZ());
        if (lastProgressPos != null && current.squaredDistanceTo(lastProgressPos) < 0.03 * 0.03) {
            noMoveTicks++;
        } else {
            noMoveTicks = 0;
        }
        lastProgressPos = current;
    }

    private void pruneCluster(BotContext context) {
        currentCluster.removeIf(pos -> !isLog(context, pos));
    }

    private void removeFromCluster(BlockPos pos) {
        if (pos != null) {
            currentCluster.remove(pos);
        }
    }

    private void buildLogCluster(BotContext context, BlockPos seed) {
        currentCluster.clear();
        if (seed == null || !isLog(context, seed)) {
            return;
        }

        Set<BlockPos> visited = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        queue.add(seed.toImmutable());
        visited.add(seed.toImmutable());

        while (!queue.isEmpty() && currentCluster.size() < MAX_CLUSTER_SIZE) {
            BlockPos cur = queue.poll();
            if (!isLog(context, cur)) {
                continue;
            }

            currentCluster.add(cur.toImmutable());
            for (Direction dir : Direction.values()) {
                BlockPos next = cur.offset(dir).toImmutable();
                if (visited.add(next) && isLog(context, next)) {
                    queue.add(next);
                }
            }
        }
    }

    private BlockPos pickClusterTarget(BotContext context) {
        BlockPos origin = context.player().getBlockPos();
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;

        boolean avoidActive = avoidTarget != null && ticks < avoidTargetUntilTick;

        for (BlockPos pos : currentCluster) {
            if (!isLog(context, pos)) {
                continue;
            }

            if (avoidActive && pos.equals(avoidTarget)) {
                continue;
            }

            if (!hasExposedFace(context, pos)) {
                continue;
            }

            double dist = pos.getSquaredDistance(origin);
            double yPenalty = Math.abs(pos.getY() - origin.getY()) * 3.5;
            if (pos.getY() > origin.getY() + 1) {
                yPenalty += 25.0;
            }
            double score = dist + yPenalty;
            if (score < bestScore) {
                bestScore = score;
                best = pos.toImmutable();
            }
        }

        if (best != null) {
            return best;
        }

        // If all exposed-face filters fail, still allow any remaining log in the cluster.
        for (BlockPos pos : currentCluster) {
            if (isLog(context, pos)) {
                if (avoidActive && pos.equals(avoidTarget)) {
                    continue;
                }
                return pos.toImmutable();
            }
        }

        // If everything was filtered by temporary avoid, allow retry after timeout.
        if (avoidActive && ticks + 1 >= avoidTargetUntilTick) {
            avoidTarget = null;
            avoidTargetUntilTick = 0;
        }

        return null;
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
                    double verticalPenalty = Math.abs(pos.getY() - origin.getY()) * 3.0;
                    if (pos.getY() > origin.getY() + 1) {
                        verticalPenalty += 24.0;
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
            RaycastContext.ShapeType.OUTLINE,
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

        if (canRayHitLog(context, from, intendedLog)) {
            return intendedLog;
        }

        BlockState hitState = context.world().getBlockState(hitPos);
        if (isClearableObstacleState(context, hitPos, hitState)) {
            return hitPos.toImmutable();
        }

        return null;
    }

    private static BlockPos detectObstacleBlock(BotContext context, BlockPos target) {
        if (target == null) {
            return null;
        }

        Vec3d from = context.player().getEyePos();
        Vec3d to = Vec3d.ofCenter(target);

        BlockHitResult hit = context.world().raycast(new RaycastContext(
            from,
            to,
            RaycastContext.ShapeType.OUTLINE,
            RaycastContext.FluidHandling.NONE,
            context.player()
        ));

        if (hit.getType() != HitResult.Type.BLOCK) {
            return null;
        }

        BlockPos hitPos = hit.getBlockPos();
        if (hitPos.equals(target)) {
            return null;
        }

        return hitPos.toImmutable();
    }

    private static boolean isClearableObstacle(BotContext context, BlockPos pos) {
        if (pos == null) {
            return false;
        }

        BlockState state = context.world().getBlockState(pos);
        return isClearableObstacleState(context, pos, state);
    }

    private static boolean isClearableObstacleState(BotContext context, BlockPos pos, BlockState state) {
        if (state.isAir() || !state.getFluidState().isEmpty()) {
            return false;
        }

        // We only clear obstacles that are breakable by hand (no required tool).
        if (state.isToolRequired()) {
            return false;
        }

        // Ignore unbreakable blocks (e.g., bedrock).
        return state.getHardness(context.world(), pos) >= 0.0F;
    }

    private static boolean hasDirectLineOfSight(BotContext context, BlockPos target) {
        Vec3d from = context.player().getEyePos();
        Vec3d to = Vec3d.ofCenter(target);

        BlockHitResult hit = context.world().raycast(new RaycastContext(
            from,
            to,
            RaycastContext.ShapeType.OUTLINE,
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
            RaycastContext.ShapeType.OUTLINE,
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
        double dx = target.x - player.getX();
        double dz = target.z - player.getZ();

        // If the target is nearly directly overhead/underfoot, atan2 is undefined and
        // would cause the bot to spin in circles. Stand still and let the caller mine.
        if (dx * dx + dz * dz < 0.5 * 0.5) {
            return;
        }

        context.actions().setForward(true);
        context.actions().setSprint(true);

        // Add simple steering so the bot can path around trunks/leaves without hard retarget hops.
        float desiredYaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float yawDelta = MathHelper.wrapDegrees(desiredYaw - player.getYaw());
        player.setYaw(player.getYaw() + MathHelper.clamp(yawDelta, -3.0F, 3.0F));
        player.setPitch(MathHelper.clamp(player.getPitch(), -25.0F, 25.0F));

        if (yawDelta > 35.0F) {
            context.actions().setRight(true);
        } else if (yawDelta < -35.0F) {
            context.actions().setLeft(true);
        }

        // Jump immediately when a solid block is in front at feet or head level.
        BlockPos aheadFeet = player.getBlockPos().offset(player.getHorizontalFacing());
        BlockPos aheadHead = aheadFeet.up();
        boolean blockedAhead = !context.world().getBlockState(aheadFeet).isAir() || !context.world().getBlockState(aheadHead).isAir();
        if (player.isOnGround() && blockedAhead) {
            context.actions().setJump(true);
        }

        double horizontalDistSq = dx * dx + dz * dz;
        if (player.isOnGround() && jumpPeriodTicks > 0 && horizontalDistSq > 1.2 * 1.2 && player.age % jumpPeriodTicks == 0) {
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
            " s=" + state +
            " target=" + target +
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
        player.setPitch(MathHelper.clamp(player.getPitch() + pitchStep, -80.0F, 55.0F));
    }

    private static Direction sideClosestToPlayer(ClientPlayerEntity player, Vec3d blockCenter) {
        double dx = player.getX() - blockCenter.x;
        double dy = player.getEyeY() - blockCenter.y;
        double dz = player.getZ() - blockCenter.z;
        return Direction.getFacing(dx, dy, dz);
    }
}