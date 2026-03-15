package com.speedrunbot.bot.task;

import com.speedrunbot.bot.BotContext;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.entity.passive.AnimalEntity;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.Comparator;
import java.util.List;

public final class HuntAnimalsTask implements BotTask {

    private static final int MEAT_TARGET       = 8;
    private static final int SEARCH_RADIUS     = 32;
    private static final int MAX_FIND_TICKS    = 20 * 20; // 20 seconds to find an animal
    private static final double ATTACK_RANGE_SQ = 3.0 * 3.0;

    private static final Item[] MEATS = {
        Items.BEEF, Items.PORKCHOP, Items.CHICKEN,
        Items.MUTTON, Items.RABBIT, Items.COOKED_BEEF,
        Items.COOKED_PORKCHOP, Items.COOKED_CHICKEN, Items.COOKED_MUTTON
    };

    private static final Item[] AXES = {
        Items.NETHERITE_AXE, Items.DIAMOND_AXE, Items.IRON_AXE,
        Items.STONE_AXE, Items.GOLDEN_AXE, Items.WOODEN_AXE
    };

    private enum State { FIND_TARGET, CHASE_AND_ATTACK, DONE }

    private State state = State.FIND_TARGET;
    private int stateTicks;
    private AnimalEntity target;
    // True once we have attacked during the current airborne arc;
    // reset when the bot lands again so each jump yields exactly one crit attack.
    private boolean critAttackedThisAir;

    @Override
    public String name() { return "HuntAnimals"; }

    @Override
    public void start(BotContext context) {
        state = State.FIND_TARGET;
        stateTicks = 0;
        target = null;
        critAttackedThisAir = false;
    }

    @Override
    public void tick(BotContext context) {
        stateTicks++;
        switch (state) {
            case FIND_TARGET      -> tickFindTarget(context);
            case CHASE_AND_ATTACK -> tickChaseAndAttack(context);
            case DONE             -> {}
        }
    }

    @Override
    public boolean isFinished(BotContext context) {
        return state == State.DONE;
    }

    // -------------------------------------------------------------------------

    private void tickFindTarget(BotContext context) {
        ClientPlayerEntity player = context.player();

        if (countMeat(player) >= MEAT_TARGET) {
            player.sendMessage(Text.literal("[SpeedrunBot] Hunt done: " + countMeat(player) + " meat"), true);
            transition(State.DONE);
            return;
        }

        if (stateTicks > MAX_FIND_TICKS) {
            player.sendMessage(Text.literal("[SpeedrunBot] No animals found nearby, giving up"), true);
            transition(State.DONE);
            return;
        }

        AnimalEntity nearest = findNearestAnimal(context);
        if (nearest != null) {
            target = nearest;
            equipBestAxe(player);
            critAttackedThisAir = false;
            transition(State.CHASE_AND_ATTACK);
        }
    }

    private void tickChaseAndAttack(BotContext context) {
        ClientPlayerEntity player = context.player();

        if (countMeat(player) >= MEAT_TARGET) {
            player.sendMessage(Text.literal("[SpeedrunBot] Hunt done: " + countMeat(player) + " meat"), true);
            transition(State.DONE);
            return;
        }

        if (target == null || target.isRemoved() || target.isDead()) {
            target = null;
            transition(State.FIND_TARGET);
            return;
        }

        // Re-equip axe each tick in case inventory changed
        equipBestAxe(player);

        Vec3d aimPos = new Vec3d(target.getX(), target.getY() + (target.getHeight() / 2.0), target.getZ());
        double distSq = player.squaredDistanceTo(target.getX(), target.getY(), target.getZ());

        lookAt(player, aimPos);

        if (distSq > ATTACK_RANGE_SQ) {
            // Chase the animal
            moveToward(context, new Vec3d(target.getX(), target.getY(), target.getZ()), 12);
            return;
        }

        // In attack range — stop moving forward
        // Critical hit: jump from ground, attack while falling
        if (player.isOnGround()) {
            critAttackedThisAir = false;
            context.actions().setJump(true);   // queue jump for next physics tick
        } else if (player.getVelocity().y < 0 && !critAttackedThisAir) {
            // Falling — this is the crit window
            ClientPlayerInteractionManager im = context.client().interactionManager;
            if (im != null) {
                im.attackEntity(player, target);
                player.swingHand(Hand.MAIN_HAND);
                context.actions().setAttack(true);
                critAttackedThisAir = true;
            }
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    @SuppressWarnings("null")
    private AnimalEntity findNearestAnimal(BotContext context) {
        ClientPlayerEntity player = context.player();
        Vec3d pos = new Vec3d(player.getX(), player.getY(), player.getZ());
        Box searchBox = new Box(
            pos.x - SEARCH_RADIUS, pos.y - SEARCH_RADIUS, pos.z - SEARCH_RADIUS,
            pos.x + SEARCH_RADIUS, pos.y + SEARCH_RADIUS, pos.z + SEARCH_RADIUS
        );
        List<AnimalEntity> animals = context.world().getEntitiesByClass(
            AnimalEntity.class, searchBox,
            e -> !e.isRemoved() && !e.isDead() && !e.isBaby()
        );
        return animals.stream()
            .min(Comparator.comparingDouble(e -> e.squaredDistanceTo(player)))
            .orElse(null);
    }

    private static int countMeat(ClientPlayerEntity player) {
        int total = 0;
        for (int i = 0; i < player.getInventory().size(); i++) {
            for (Item meat : MEATS) {
                if (player.getInventory().getStack(i).isOf(meat)) {
                    total += player.getInventory().getStack(i).getCount();
                }
            }
        }
        return total;
    }

    private static void equipBestAxe(ClientPlayerEntity player) {
        for (Item axe : AXES) {
            for (int i = 0; i < player.getInventory().size(); i++) {
                if (!player.getInventory().getStack(i).isOf(axe)) continue;
                if (i < 9) {
                    player.getInventory().setSelectedSlot(i);
                    if (player.networkHandler != null)
                        player.networkHandler.sendPacket(new UpdateSelectedSlotC2SPacket(i));
                } else {
                    // Swap into hotbar slot 0
                    var held = player.getInventory().getStack(0).copy();
                    player.getInventory().setStack(0, player.getInventory().getStack(i).copy());
                    player.getInventory().setStack(i, held);
                    player.getInventory().setSelectedSlot(0);
                    if (player.networkHandler != null)
                        player.networkHandler.sendPacket(new UpdateSelectedSlotC2SPacket(0));
                }
                return;
            }
        }
        // No axe — equip empty hand
        for (int s = 0; s < 9; s++) {
            if (player.getInventory().getStack(s).isEmpty()) {
                player.getInventory().setSelectedSlot(s);
                if (player.networkHandler != null)
                    player.networkHandler.sendPacket(new UpdateSelectedSlotC2SPacket(s));
                return;
            }
        }
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
        player.setYaw(player.getYaw() + MathHelper.clamp(yawDelta, -4.0F, 4.0F));

        if (yawDelta > 35.0F) {
            context.actions().setRight(true);
        } else if (yawDelta < -35.0F) {
            context.actions().setLeft(true);
        }

        double horizontalDistSq = dx * dx + dz * dz;
        if (player.isOnGround() && jumpPeriodTicks > 0 && horizontalDistSq > 1.2 * 1.2
                && player.age % jumpPeriodTicks == 0) {
            context.actions().setJump(true);
        }
    }

    private void transition(State next) {
        state = next;
        stateTicks = 0;
    }
}
