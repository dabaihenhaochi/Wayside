package io.github.dabaihenhaochi.wayside.entity.ai;

import io.github.dabaihenhaochi.wayside.entity.NpcEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;

public class NpcMoveToTargetGoal extends Goal {

    private static final int RETRY_INTERVAL_TICKS = 20;
    private static final long MAX_TARGET_TICKS = 600L;

    private final NpcEntity npc;
    private final double speedModifier;

    private BlockPos activeTarget;

    private int retryCooldown;
    private long targetStartTime;

    public NpcMoveToTargetGoal(NpcEntity npc, double speedModifier) {
        this.npc = npc;
        this.speedModifier = speedModifier;

        this.setFlags(EnumSet.of(Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        return this.npc.hasMoveTarget();
    }

    @Override
    public boolean canContinueToUse() {
        return this.npc.hasMoveTarget();
    }

    @Override
    public void start() {
        BlockPos target = this.npc.getMoveTarget();

        if (target == null) {
            return;
        }

        this.activeTarget = target;
        this.targetStartTime = this.npc.level().getGameTime();
        this.retryCooldown = 0;

        this.tryMoveTo(target);
    }

    @Override
    public void tick() {
        BlockPos target = this.npc.getMoveTarget();

        if (target == null) {
            return;
        }

        // 上层改变了目标，例如玩家重新右键 NPC
        if (!target.equals(this.activeTarget)) {
            this.activeTarget = target;
            this.targetStartTime = this.npc.level().getGameTime();
            this.retryCooldown = 0;

            this.tryMoveTo(target);
            return;
        }

        long elapsedTicks =
                this.npc.level().getGameTime() - this.targetStartTime;

        // 已经到达目标
        if (this.hasReachedTarget(target)) {
            this.npc.getNavigation().stop();
            this.npc.clearMoveTarget();
            return;
        }

        // 整个任务持续太久，真正放弃目标
        if (elapsedTicks >= MAX_TARGET_TICKS) {
            this.npc.getNavigation().stop();

            BlockPos blockTarget = this.npc.getBlockTarget();

            if (blockTarget != null && blockTarget.equals(target)) {
                this.npc.markBlockTargetFailed(blockTarget);
                this.npc.clearBlockTarget();
            }

            this.npc.clearMoveTarget();
            return;
        }

        // Navigation 已经停了，但 NPC 还没到目标
        // 说明当前这一次寻路失败或结束了
        if (this.npc.getNavigation().isDone()) {

            if (this.retryCooldown > 0) {
                this.retryCooldown--;
                return;
            }

            this.tryMoveTo(target);
        }
    }

    @Override
    public void stop() {
        this.npc.getNavigation().stop();

        this.activeTarget = null;
        this.retryCooldown = 0;
    }

    private void tryMoveTo(BlockPos target) {
        if (target == null) {
            return;
        }

        this.npc.getNavigation().moveTo(
                target.getX() + 0.5,
                target.getY(),
                target.getZ() + 0.5,
                this.speedModifier
        );

        this.retryCooldown = RETRY_INTERVAL_TICKS;
    }

    private boolean hasReachedTarget(BlockPos target) {
        double targetX = target.getX() + 0.5;
        double targetY = target.getY();
        double targetZ = target.getZ() + 0.5;

        double deltaX = this.npc.getX() - targetX;
        double deltaY = this.npc.getY() - targetY;
        double deltaZ = this.npc.getZ() - targetZ;

        double distanceSquared =
                deltaX * deltaX
                        + deltaY * deltaY
                        + deltaZ * deltaZ;

        return distanceSquared <= 2.25;
    }
}