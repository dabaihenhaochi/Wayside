package io.github.dabaihenhaochi.wayside.entity.ai;

import io.github.dabaihenhaochi.wayside.entity.NpcEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.item.ItemEntity;

import java.util.Comparator;
import java.util.EnumSet;

public class NpcCollectItemGoal extends Goal {

    private final NpcEntity npc;
    private ItemEntity target;
    private long startTime;

    public NpcCollectItemGoal(NpcEntity npc) {
        this.npc = npc;
        this.setFlags(EnumSet.of(Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        if (npc.hasBlockTarget() || npc.hasMoveTarget() || !npc.needsLogs()) {
            return false;
        }

        target = npc.level().getEntitiesOfClass(
                ItemEntity.class,
                npc.getBoundingBox().inflate(8),
                item -> item.isAlive()
                        && npc.wantsItem(item.getItem())
                        && npc.getInventory().canAddItem(item.getItem())
        ).stream().min(
                Comparator.comparingDouble(npc::distanceToSqr)
        ).orElse(null);

        return target != null;
    }

    @Override
    public void start() {
        startTime = npc.level().getGameTime();
        npc.getNavigation().moveTo(target, 1.0);
    }

    @Override
    public boolean canContinueToUse() {
        return target != null
                && target.isAlive()
                && !npc.hasBlockTarget()
                && !npc.hasMoveTarget()
                && npc.wantsItem(target.getItem())
                && npc.level().getGameTime() - startTime < 200;
    }

    @Override
    public void tick() {

        if (npc.getNavigation().isDone()
                && npc.distanceToSqr(target) < 9.0
                && Math.abs(npc.getY() - target.getY()) < 1.0
                && npc.hasLineOfSight(target)) {

            npc.getMoveControl().setWantedPosition(
                    target.getX(),
                    target.getY(),
                    target.getZ(),
                    1.0
            );

        } else if (npc.tickCount % 20 == 0) {
            npc.getNavigation().moveTo(target, 1.0);
        }
    }

    @Override
    public void stop() {
        npc.getNavigation().stop();
        target = null;
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }
}