package io.github.dabaihenhaochi.wayside.entity.ai;

import io.github.dabaihenhaochi.wayside.entity.NpcEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Optional;
import java.util.function.Predicate;

public class NpcFindBlockGoal extends Goal {

    private static final int SCAN_INTERVAL_TICKS = 20;
    private static final int HORIZONTAL_SCAN_RANGE = 8;
    private static final int VERTICAL_SCAN_RANGE = 4;

    private final NpcEntity npc;
    private final Predicate<BlockState> targetPredicate;

    private long nextScanTime;

    public NpcFindBlockGoal(
            NpcEntity npc,
            Predicate<BlockState> targetPredicate
    ) {
        this.npc = npc;
        this.targetPredicate = targetPredicate;
    }

    @Override
    public boolean canUse() {
        BlockPos target = npc.getBlockTarget();

        if (target != null) {
            // 保留有效资源；被推离后等落地，再为同一资源重新选择站位。
            if (npc.level().hasChunkAt(target)
                    && targetPredicate.test(npc.level().getBlockState(target))) {
                return npc.onGround()
                        && !npc.hasMoveTarget()
                        && !npc.canReachBlock(target)
                        && npc.level().getGameTime() >= nextScanTime;
            }

            // 原目标已失效，清理状态
            npc.clearBlockTarget();
        }

        return npc.onGround()
                && !npc.hasMoveTarget()
                && npc.level().getGameTime() >= nextScanTime;
    }

    @Override
    public void start() {
        this.nextScanTime =
                this.npc.level().getGameTime() + SCAN_INTERVAL_TICKS;
        Optional<BlockPos> target = npc.hasBlockTarget()
                ? Optional.of(npc.getBlockTarget())
                : BlockPos.findClosestMatch(
                npc.blockPosition(),
                HORIZONTAL_SCAN_RANGE,
                VERTICAL_SCAN_RANGE,
                pos -> npc.level().hasChunkAt(pos) && targetPredicate.test(
                        npc.level().getBlockState(pos)
                ) && !npc.isBlockTargetOnCooldown(pos)
        );

        if (target.isPresent()) {
            BlockPos targetPos = target.get().immutable();

            npc.setBlockTarget(targetPos);
            if (!npc.planMoveToBlockTarget()) {
                npc.markBlockTargetFailed(targetPos);
                npc.clearBlockTarget();
            }
        }
    }

    @Override
    public boolean canContinueToUse() {
        return false;
    }
}
