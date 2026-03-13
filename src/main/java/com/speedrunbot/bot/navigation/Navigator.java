package com.speedrunbot.bot.navigation;

import com.speedrunbot.bot.BotContext;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import net.minecraft.block.BlockState;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

public final class Navigator {
    private static final int PATH_RECALC_INTERVAL_TICKS = 10;
    private static final int PATH_MAX_NODES = 600;
    private static final int PATH_SEARCH_RADIUS = 14;
    private static final int PATH_STUCK_TICKS = 35;

    private List<BlockPos> currentPath = List.of();
    private int pathIndex;
    private int pathRecalcCooldown;
    private BlockPos pathGoal;
    private Vec3d lastPathPos;
    private int pathStuckTicks;

    public void tickCooldowns() {
        if (pathRecalcCooldown > 0) {
            pathRecalcCooldown--;
        }
    }

    public void reset() {
        currentPath = List.of();
        pathIndex = 0;
        pathRecalcCooldown = 0;
        pathGoal = null;
        lastPathPos = null;
        pathStuckTicks = 0;
    }

    public void navigateTo(BotContext context, BlockPos goal, Vec3d fallbackTargetCenter, int jumpPeriodTicks) {
        BlockPos start = context.player().getBlockPos();

        boolean needReplan = currentPath.isEmpty() || pathIndex >= currentPath.size() || pathGoal == null || !pathGoal.equals(goal);
        if (!needReplan && pathRecalcCooldown == 0) {
            needReplan = true;
        }

        if (needReplan) {
            List<BlockPos> newPath = findPath(context, start, goal);
            if (newPath.isEmpty()) {
                moveToward(context, fallbackTargetCenter, jumpPeriodTicks);
                pathRecalcCooldown = PATH_RECALC_INTERVAL_TICKS;
                return;
            }

            currentPath = newPath;
            pathIndex = 0;
            pathGoal = goal;
            pathRecalcCooldown = PATH_RECALC_INTERVAL_TICKS;
            lastPathPos = null;
            pathStuckTicks = 0;
        }

        while (pathIndex < currentPath.size()) {
            BlockPos waypoint = currentPath.get(pathIndex);
            double wpDistSq = context.player().squaredDistanceTo(waypoint.getX() + 0.5, context.player().getY(), waypoint.getZ() + 0.5);
            if (wpDistSq < 0.85 * 0.85) {
                pathIndex++;
                continue;
            }

            moveToward(context, Vec3d.ofCenter(waypoint), jumpPeriodTicks);
            updatePathStuckState(context);
            return;
        }

        moveToward(context, fallbackTargetCenter, jumpPeriodTicks);
    }

    public static boolean canStandAt(BotContext context, BlockPos pos) {
        BlockState feet = context.world().getBlockState(pos);
        BlockState head = context.world().getBlockState(pos.up());
        BlockState floor = context.world().getBlockState(pos.down());

        boolean feetFree = feet.getCollisionShape(context.world(), pos).isEmpty() || feet.isIn(BlockTags.LEAVES);
        boolean headFree = head.getCollisionShape(context.world(), pos.up()).isEmpty() || head.isIn(BlockTags.LEAVES);
        boolean floorSolid = !floor.getCollisionShape(context.world(), pos.down()).isEmpty();

        return feetFree && headFree && floorSolid;
    }

    private void updatePathStuckState(BotContext context) {
        Vec3d current = new Vec3d(context.player().getX(), context.player().getY(), context.player().getZ());
        if (lastPathPos != null && current.squaredDistanceTo(lastPathPos) < 0.02 * 0.02) {
            pathStuckTicks++;
        } else {
            pathStuckTicks = 0;
        }

        lastPathPos = current;

        if (pathStuckTicks > PATH_STUCK_TICKS) {
            reset();
        }
    }

    private static List<BlockPos> findPath(BotContext context, BlockPos start, BlockPos goal) {
        if (start.equals(goal)) {
            return List.of(goal);
        }

        PriorityQueue<Node> open = new PriorityQueue<>(Comparator.comparingDouble(n -> n.fScore));
        Map<BlockPos, BlockPos> cameFrom = new HashMap<>();
        Map<BlockPos, Double> gScore = new HashMap<>();
        Set<BlockPos> closed = new HashSet<>();

        gScore.put(start, 0.0);
        open.add(new Node(start, heuristic(start, goal)));

        int expanded = 0;
        while (!open.isEmpty() && expanded < PATH_MAX_NODES) {
            Node current = open.poll();
            if (closed.contains(current.pos)) {
                continue;
            }

            closed.add(current.pos);
            expanded++;

            if (current.pos.equals(goal)) {
                return reconstructPath(cameFrom, current.pos);
            }

            for (BlockPos neighbor : neighbors(context, start, current.pos)) {
                if (closed.contains(neighbor)) {
                    continue;
                }

                double tentativeG = gScore.getOrDefault(current.pos, Double.MAX_VALUE) + movementCost(current.pos, neighbor);
                if (tentativeG >= gScore.getOrDefault(neighbor, Double.MAX_VALUE)) {
                    continue;
                }

                cameFrom.put(neighbor, current.pos);
                gScore.put(neighbor, tentativeG);
                double f = tentativeG + heuristic(neighbor, goal);
                open.add(new Node(neighbor, f));
            }
        }

        return List.of();
    }

    private static List<BlockPos> reconstructPath(Map<BlockPos, BlockPos> cameFrom, BlockPos end) {
        List<BlockPos> path = new ArrayList<>();
        BlockPos cursor = end;
        path.add(cursor);
        while (cameFrom.containsKey(cursor)) {
            cursor = cameFrom.get(cursor);
            path.add(0, cursor);
        }
        return path;
    }

    private static double heuristic(BlockPos a, BlockPos b) {
        int dx = Math.abs(a.getX() - b.getX());
        int dy = Math.abs(a.getY() - b.getY());
        int dz = Math.abs(a.getZ() - b.getZ());
        return dx + dz + (dy * 1.5);
    }

    private static double movementCost(BlockPos a, BlockPos b) {
        int dy = Math.abs(a.getY() - b.getY());
        return 1.0 + (dy > 0 ? 0.35 : 0.0);
    }

    private static List<BlockPos> neighbors(BotContext context, BlockPos origin, BlockPos current) {
        List<BlockPos> out = new ArrayList<>(8);

        if (Math.abs(current.getX() - origin.getX()) > PATH_SEARCH_RADIUS || Math.abs(current.getZ() - origin.getZ()) > PATH_SEARCH_RADIUS) {
            return out;
        }

        for (Direction dir : Direction.Type.HORIZONTAL) {
            BlockPos flat = current.offset(dir);
            if (canStandAt(context, flat)) {
                out.add(flat.toImmutable());
            }

            BlockPos up = flat.up();
            if (Math.abs(up.getY() - origin.getY()) <= 2 && canStandAt(context, up)) {
                out.add(up.toImmutable());
            }

            BlockPos down = flat.down();
            if (Math.abs(down.getY() - origin.getY()) <= 2 && canStandAt(context, down)) {
                out.add(down.toImmutable());
            }
        }

        return out;
    }

    private static void moveToward(BotContext context, Vec3d target, int jumpPeriodTicks) {
        ClientPlayerEntity player = context.player();
        context.actions().setForward(true);
        context.actions().setSprint(true);

        double dx = target.x - player.getX();
        double dz = target.z - player.getZ();
        double horizontalDistSq = dx * dx + dz * dz;
        float desiredYaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float yawDelta = MathHelper.wrapDegrees(desiredYaw - player.getYaw());
        player.setYaw(player.getYaw() + MathHelper.clamp(yawDelta, -3.0F, 3.0F));
        player.setPitch(MathHelper.clamp(player.getPitch(), -25.0F, 25.0F));

        if (yawDelta > 35.0F) {
            context.actions().setRight(true);
        } else if (yawDelta < -35.0F) {
            context.actions().setLeft(true);
        }

        if (player.isOnGround() && jumpPeriodTicks > 0 && horizontalDistSq > 1.2 * 1.2 && player.age % jumpPeriodTicks == 0) {
            context.actions().setJump(true);
        }
    }

    private record Node(BlockPos pos, double fScore) {
    }
}