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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class AutoMineNearestLogTask implements BotTask {
    private static final Logger LOGGER = LoggerFactory.getLogger(AutoMineNearestLogTask.class);
    private static final boolean DEBUG_TELEMETRY = true;
    private static final int DEBUG_PRINT_INTERVAL_TICKS = 10;

    private static final int SEARCH_RADIUS_XZ = 8;
    private static final int SEARCH_RADIUS_Y = 4;
    private static final double MINE_RANGE_SQ = 4.5 * 4.5;
    private static final int MAX_TICKS = 20 * 35;

    private BlockPos targetLog;
    private int ticks;
    private boolean completed;
    private int retargetCooldown;
    private int startingLogItemCount;
    private int targetAgeTicks;
    private int targetMissingTicks;

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
        context.player().sendMessage(Text.literal("[SpeedrunBot] Searching for nearby logs"), true);
        debug(context, "start logs=" + startingLogItemCount);
    }

    @Override
    public void tick(BotContext context) {
        ticks++;

        if (countLogItems(context.player()) > startingLogItemCount) {
            completed = true;
            context.player().sendMessage(Text.literal("[SpeedrunBot] Collected log item"), true);
            debug(context, "finish reason=collected_log");
            return;
        }

        if (retargetCooldown > 0) {
            retargetCooldown--;
        }

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
            debug(context, "drop_target reason=missing_too_long");
        }

        if (targetLog == null && retargetCooldown == 0) {
            targetLog = findNearestLog(context);
            retargetCooldown = 20;
            targetAgeTicks = 0;

            if (targetLog != null) {
                context.player().sendMessage(
                    Text.literal("[SpeedrunBot] Target log at " + targetLog.toShortString()),
                    true
                );
                debug(context, "acquire_target pos=" + targetLog.toShortString());
            } else {
                debug(context, "acquire_target none");
            }
        }

        if (targetLog == null) {
            // Gentle roam so the bot can discover trees without standing still forever.
            moveToward(
                context,
                context.player().getRotationVec(1.0F).add(context.player().getX(), context.player().getY(), context.player().getZ()),
                24
            );
            debug(context, "roam no_target cooldown=" + retargetCooldown);
            return;
        }

        targetAgeTicks++;
        if (targetAgeTicks > 20 * 12) {
            // Hard reset target if we spent too long on it to avoid tree-to-tree jitter loops.
            targetLog = null;
            targetAgeTicks = 0;
            targetMissingTicks = 0;
            retargetCooldown = 20;
            debug(context, "drop_target reason=aged_out");
            return;
        }

        Vec3d targetCenter = Vec3d.ofCenter(targetLog);
        lookAt(context.player(), targetCenter);

        double distanceSq = context.player().squaredDistanceTo(targetCenter.x, targetCenter.y, targetCenter.z);
        if (distanceSq > MINE_RANGE_SQ) {
            moveToward(context, targetCenter, 12);
            debug(context, "move_to_target dist=" + String.format("%.2f", Math.sqrt(distanceSq)) + " target=" + targetLog.toShortString());
            return;
        }

        ClientPlayerInteractionManager interaction = context.client().interactionManager;
        if (interaction == null) {
            return;
        }

        BlockPos mineTarget = resolveMineTarget(context, targetLog);
        if (mineTarget == null) {
            // Path around obstacles by continuing to move and occasionally hop.
            moveToward(context, targetCenter, 14);
            debug(context, "mine_target none (obstructed) target=" + targetLog.toShortString());
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
            targetLog = null;
            retargetCooldown = 0;
            targetAgeTicks = 0;
            targetMissingTicks = 0;
            debug(context, "target_log_not_present waiting_for_pickup_or_new_target");
        }
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
        ticks = 0;
        completed = false;
        retargetCooldown = 0;
        startingLogItemCount = 0;
        targetAgeTicks = 0;
        targetMissingTicks = 0;
        debug(context, "stop");
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

    private static void moveToward(BotContext context, Vec3d target, int jumpPeriodTicks) {
        ClientPlayerEntity player = context.player();
        context.actions().setForward(true);
        context.actions().setSprint(true);

        // Add simple steering so the bot can path around trunks/leaves without hard retarget hops.
        double dx = target.x - player.getX();
        double dz = target.z - player.getZ();
        float desiredYaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float yawDelta = MathHelper.wrapDegrees(desiredYaw - player.getYaw());
        if (yawDelta > 20.0F) {
            context.actions().setRight(true);
        } else if (yawDelta < -20.0F) {
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
        context.player().sendMessage(Text.literal(line), true);
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