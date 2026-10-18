package io.github.dabaihenhaochi.wayside.entity.ai;

import io.github.dabaihenhaochi.wayside.entity.NpcEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.EventHooks;

import java.util.EnumSet;

public class NpcHarvestBlockGoal extends Goal {

    private static final long WORK_DURATION_TICKS = 40L;
    private static final long SWING_INTERVAL_TICKS = 10L;

    private final NpcEntity npc;
    private BlockPos activeTarget;
    private BlockState activeState;
    private long workStartTime;
    private long nextSwingTime;
    private int lastProgress = -1;

    public NpcHarvestBlockGoal(NpcEntity npc) {
        this.npc = npc;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        BlockPos target = this.npc.getBlockTarget();
        return !this.npc.level().isClientSide
                && !this.npc.hasMoveTarget()
                && this.isValidTarget(target)
                && this.npc.canReachBlock(target)
                && EventHooks.canEntityGrief(this.npc.level(), this.npc);
    }

    @Override
    public boolean canContinueToUse() {
        return this.activeTarget != null
                && this.activeTarget.equals(this.npc.getBlockTarget())
                && !this.npc.hasMoveTarget()
                && this.isValidTarget(this.activeTarget)
                && this.activeState.equals(this.npc.level().getBlockState(this.activeTarget))
                && this.npc.canReachBlock(this.activeTarget)
                && EventHooks.canEntityGrief(this.npc.level(), this.npc);
    }

    @Override
    public void start() {
        this.activeTarget = this.npc.getBlockTarget().immutable();
        this.activeState = this.npc.level().getBlockState(this.activeTarget);
        this.workStartTime = this.npc.level().getGameTime();
        this.nextSwingTime = this.workStartTime;
        this.lastProgress = -1;
        this.npc.getNavigation().stop();
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        // 每次修改世界前重新核验，不能只依赖 GoalSelector 的检查频率。
        if (!this.canContinueToUse()) {
            this.workStartTime = this.npc.level().getGameTime();
            this.clearProgress();
            return;
        }

        Vec3 center = Vec3.atCenterOf(this.activeTarget);
        this.npc.getLookControl().setLookAt(center.x, center.y, center.z);

        long now = this.npc.level().getGameTime();
        if (now >= this.nextSwingTime) {
            this.npc.swing(InteractionHand.MAIN_HAND);
            this.nextSwingTime = now + SWING_INTERVAL_TICKS;
        }

        long elapsedTicks = now - this.workStartTime;
        int progress = (int) Math.min(9L, elapsedTicks * 10L / WORK_DURATION_TICKS);
        if (progress != this.lastProgress) {
            this.npc.level().destroyBlockProgress(this.npc.getId(), this.activeTarget, progress);
            this.lastProgress = progress;
        }

        if (elapsedTicks >= WORK_DURATION_TICKS) {
            boolean allowed = this.activeState.canEntityDestroy(
                    this.npc.level(), this.activeTarget, this.npc
            ) && EventHooks.onEntityDestroyBlock(this.npc, this.activeTarget, this.activeState);

            if (!allowed || !this.npc.level().destroyBlock(this.activeTarget, true, this.npc)) {
                this.npc.markBlockTargetFailed(this.activeTarget);
            }

            this.clearProgress();
            this.npc.clearBlockTarget();
        }
    }

    @Override
    public void stop() {
        this.clearProgress();

        // 工作被推离或遮挡时重新接入移动；玩家手动命令始终保留。
        if (this.activeTarget != null
                && this.activeTarget.equals(this.npc.getBlockTarget())
                && !this.npc.hasMoveTarget()
                && this.isValidTarget(this.activeTarget)
                && this.npc.onGround()
                && !this.npc.canReachBlock(this.activeTarget)) {
            if (!this.npc.planMoveToBlockTarget()) {
                this.npc.markBlockTargetFailed(this.activeTarget);
                this.npc.clearBlockTarget();
            }
        }

        this.activeTarget = null;
        this.activeState = null;
    }

    private boolean isValidTarget(BlockPos target) {
        return target != null
                && this.npc.level().hasChunkAt(target)
                && this.npc.level().getBlockState(target).is(BlockTags.LOGS);
    }

    private void clearProgress() {
        if (this.activeTarget != null) {
            this.npc.level().destroyBlockProgress(this.npc.getId(), this.activeTarget, -1);
        }
        this.lastProgress = -1;
    }
}
