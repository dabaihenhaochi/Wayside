package io.github.dabaihenhaochi.wayside.entity.task.build;

import io.github.dabaihenhaochi.wayside.entity.task.FailureMemory;

import io.github.dabaihenhaochi.wayside.building.ConstructionOperation;
import io.github.dabaihenhaochi.wayside.entity.NpcEntity;
import io.github.dabaihenhaochi.wayside.entity.action.ActionResult;
import io.github.dabaihenhaochi.wayside.entity.ai.NpcBlockInteraction;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** 仅负责施工项排序、可执行性与站位选择，不推进动作或保存任务。 */
final class ConstructionWorkSelector {
    private static final int CANDIDATES_PER_TICK = 8;
    private List<ConstructionOperation> candidates = List.of();
    private int cursor;
    private FailureMemory<BlockPos> failures = new FailureMemory<>();
    private ActionResult.Failure blockedBy = ActionResult.Failure.UNREACHABLE;
    private final boolean foundation;

    ConstructionWorkSelector(boolean foundation) { this.foundation = foundation; }

    record Work(ConstructionOperation operation, BlockState expectedState, BlockPos stand, boolean foundation) {
        boolean breaking() { return operation.action() != ConstructionOperation.Action.PLACE; }
        private boolean supported(ServerLevel level) {
            BlockPos below = operation.pos().below();
            return !foundation || breaking() || (level.getFluidState(below).isEmpty()
                    && level.getBlockState(below).isFaceSturdy(level, below, Direction.UP));
        }
        boolean matches(ServerLevel level) {
            if (!level.isLoaded(operation.pos())) return false;
            BlockState current = level.getBlockState(operation.pos());
            return supported(level) && (breaking() ? current.equals(expectedState) : current.isAir());
        }
        boolean canOperate(NpcEntity npc) {
            return supported((ServerLevel) npc.level()) && (breaking()
                    ? NpcBlockInteraction.canWorkAt(npc, operation.pos(), npc.position())
                    : NpcBlockInteraction.canPlaceAt(npc, operation.pos(), operation.targetState(), npc.position()));
        }
    }

    void prepare(List<ConstructionOperation> tasks, NpcEntity npc) {
        prepare(tasks, npc, Integer.MIN_VALUE);
    }

    void prepare(List<ConstructionOperation> tasks, NpcEntity npc, int floorY) {
        ServerLevel level = (ServerLevel) npc.level();
        var sorted = new ArrayList<>(tasks);
        // 先做房屋底层，避免低处被墙封住；随后保留屋顶视线。地基从低处填起。
        sorted.sort(Comparator.comparingInt((ConstructionOperation task) -> priority(level, task, floorY))
                .thenComparingInt(task -> foundation && task.action() == ConstructionOperation.Action.PLACE
                        ? task.pos().getY() : -task.pos().getY())
                .thenComparingDouble(task -> npc.position().distanceToSqr(Vec3.atCenterOf(task.pos()))));
        candidates = List.copyOf(sorted);
        cursor = 0;
    }

    Work next(NpcEntity npc) {
        ServerLevel level = (ServerLevel) npc.level();
        long now = level.getGameTime();
        int attempts = 0;
        while (cursor < candidates.size() && attempts++ < CANDIDATES_PER_TICK) {
            ConstructionOperation candidate = candidates.get(cursor++);
            if (!level.isLoaded(candidate.pos()) || failures.isCoolingDown(candidate.pos(), now)) continue;
            BlockState current = level.getBlockState(candidate.pos());
            if (current.equals(candidate.targetState())) continue;
            Work work = new Work(candidate, current, null, foundation);
            if (work.breaking() && !NpcBlockInteraction.canDemolish(level, candidate.pos(), current)) {
                markFailed(candidate.pos(), now, ActionResult.Failure.FORBIDDEN);
                continue;
            }
            if (!work.supported(level)) continue;
            if (!work.breaking() && !NpcBlockInteraction.hasPlacementSupport(
                    level, candidate.pos(), candidate.targetState())) continue;
            if (work.canOperate(npc)) return work;
            var stand = work.breaking() ? NpcBlockInteraction.findWorkPosition(npc, candidate.pos())
                    : NpcBlockInteraction.findPlacementPosition(npc, candidate.pos(), candidate.targetState());
            if (stand.isPresent()) return new Work(candidate, current, stand.get(), foundation);
            markFailed(candidate.pos(), now, ActionResult.Failure.UNREACHABLE);
        }
        return null;
    }

    boolean exhausted() { return cursor >= candidates.size(); }
    ActionResult.Failure blockedBy() { return blockedBy; }
    void markFailed(BlockPos pos, long now, ActionResult.Failure reason) {
        blockedBy = reason;
        failures.mark(pos, now, 80);
    }
    void reset() {
        candidates = List.of();
        cursor = 0;
        failures = new FailureMemory<>();
        blockedBy = ActionResult.Failure.UNREACHABLE;
    }
    private static int priority(ServerLevel level, ConstructionOperation task, int floorY) {
        if (task.action() == ConstructionOperation.Action.REMOVE) return 0;
        if (task.action() == ConstructionOperation.Action.REPLACE
                && level.getBlockState(task.pos()).getBlock() != task.targetState().getBlock()) return 1;
        if (task.action() == ConstructionOperation.Action.PLACE) return task.pos().getY() == floorY ? 2 : 3;
        return 4;
    }
}
