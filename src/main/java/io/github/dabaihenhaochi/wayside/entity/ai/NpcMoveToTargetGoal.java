package io.github.dabaihenhaochi.wayside.entity.ai;

import io.github.dabaihenhaochi.wayside.entity.NpcEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;
import java.util.Objects;

public class NpcMoveToTargetGoal extends Goal {

    private static final int RETRY_INTERVAL_TICKS = 20;
    private static final long MAX_TARGET_TICKS = 600L;

    private final NpcEntity npc;
    private final double speedModifier;

    private BlockPos activeTarget;
    private BlockPos activeBlockTarget;

    private long nextRetryTime;
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
        this.activeBlockTarget = this.npc.getBlockTarget();
        this.targetStartTime = this.npc.level().getGameTime();
        this.nextRetryTime = 0L;

        this.tryMoveTo(target);
    }

    @Override
    public void tick() {
        BlockPos target = this.npc.getMoveTarget();

        if (target == null) {
            return;
        }

        // 上层改变了目标，例如玩家重新右键 NPC
        if (!target.equals(this.activeTarget)
                || !Objects.equals(this.activeBlockTarget, this.npc.getBlockTarget())) {
            BlockPos blockTarget = this.npc.getBlockTarget();
            // 同一块原木换站位不重置总超时，避免无法抵达时无限重新规划。
            if (blockTarget == null || !blockTarget.equals(this.activeBlockTarget)) {
                this.targetStartTime = this.npc.level().getGameTime();
            }
            this.activeTarget = target;
            this.activeBlockTarget = blockTarget;
            this.nextRetryTime = 0L;

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

            if (blockTarget != null) {
                this.npc.markBlockTargetFailed(blockTarget);
                this.npc.clearBlockTarget();
            }

            this.npc.clearMoveTarget();
            return;
        }

        // Navigation 已经停了，但 NPC 还没到目标
        // 说明当前这一次寻路失败或结束了
        if (this.npc.getNavigation().isDone()) {

            if (this.npc.level().getGameTime() < this.nextRetryTime) {
                return;
            }

            // 资源坐标和站位坐标不同，失败重试时重新检查站位及可达路径。
            if (this.npc.hasBlockTarget()) {
                if (!this.npc.onGround()) {
                    return;
                }
                if (!this.npc.planMoveToBlockTarget()) {
                    this.npc.markBlockTargetFailed(this.npc.getBlockTarget());
                    this.npc.clearBlockTarget();
                    this.npc.getNavigation().stop();
                    return;
                }
                target = this.npc.getMoveTarget();
                if (target == null) {
                    this.npc.getNavigation().stop();
                    return;
                }
                this.activeTarget = target;
            }

            this.tryMoveTo(target);
        }
    }

    @Override
    public void stop() {
        this.npc.getNavigation().stop();

        this.activeTarget = null;
        this.activeBlockTarget = null;
        this.nextRetryTime = 0L;
    }

    private void tryMoveTo(BlockPos target) {
        if (target == null) {
            return;
        }

        this.npc.getNavigation().moveTo(
                target.getX() + 0.5,
                target.getY(),
                target.getZ() + 0.5,
                this.npc.hasBlockTarget() ? 0 : 1,
                this.speedModifier
        );

        this.nextRetryTime = this.npc.level().getGameTime() + RETRY_INTERVAL_TICKS;
    }

    private boolean hasReachedTarget(BlockPos target) {
        if (this.npc.hasBlockTarget()) {
            return this.npc.canReachBlock(this.npc.getBlockTarget());
        }

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
