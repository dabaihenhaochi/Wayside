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
import net.minecraft.world.level.block.BaseTorchBlock;
import net.minecraft.world.level.block.BushBlock;
import net.minecraft.world.level.block.CarpetBlock;
import net.minecraft.world.level.block.FaceAttachedHorizontalDirectionalBlock;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.VineBlock;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Predicate;

/** 仅负责施工项排序、可执行性与站位选择，不推进动作或保存任务。 */
final class ConstructionWorkSelector {
    private static final int CANDIDATES_PER_TICK = 8;
    private static final int STATION_SEARCHES_PER_TICK = 1;
    private List<ConstructionOperation> candidates = List.of();
    private final Map<BlockPos, ConstructionOperation> navigationBlocked = new LinkedHashMap<>();
    private int cursor;
    private final FailureMemory<BlockPos> failures = new FailureMemory<>();
    private ActionResult.Failure blockedBy = ActionResult.Failure.UNREACHABLE;
    private final boolean foundation;

    ConstructionWorkSelector(boolean foundation) { this.foundation = foundation; }

    record Work(ConstructionOperation operation, BlockState expectedState, BlockPos stand,
                boolean foundation, boolean remote) {
        boolean breaking() { return operation.action() != ConstructionOperation.Action.PLACE; }
        boolean allowsFallback() { return !foundation && !breaking(); }
        Work withFallback() { return new Work(operation, expectedState, stand, foundation, true); }
        Work withNormalPlacement() { return new Work(operation, expectedState, stand, foundation, false); }
        boolean approached(NpcEntity npc) {
            double dx = npc.getX() - (operation.pos().getX() + 0.5);
            double dz = npc.getZ() - (operation.pos().getZ() + 0.5);
            return canOperate(npc) || (remote && dx * dx + dz * dz <= 16);
        }
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
        ServerLevel level = (ServerLevel) npc.level();
        var sorted = new ArrayList<>(tasks);
        // 清障从高到低；主体从低到高，逐行往返；依附方块最后补。
        sorted.sort(Comparator.comparingInt((ConstructionOperation task) -> priority(level, task))
                .thenComparingInt(task -> task.action() == ConstructionOperation.Action.PLACE
                        ? task.pos().getY() : -task.pos().getY())
                .thenComparingInt(task -> task.pos().getZ())
                .thenComparingInt(task -> Math.floorMod(task.pos().getZ(), 2) == 0
                        ? task.pos().getX() : -task.pos().getX()));
        candidates = List.copyOf(sorted);
        navigationBlocked.clear();
        cursor = 0;
    }

    Work next(NpcEntity npc) {
        return select(npc, false, CANDIDATES_PER_TICK); // 选址预检仍须找到真实入口。
    }

    Work next(NpcEntity npc, boolean allowFallback) {
        return select(npc, allowFallback, STATION_SEARCHES_PER_TICK);
    }

    private Work select(NpcEntity npc, boolean allowFallback, int searchBudget) {
        ServerLevel level = (ServerLevel) npc.level();
        long now = level.getGameTime();
        int attempts = 0;
        int searches = 0;
        while (cursor < candidates.size() && attempts++ < CANDIDATES_PER_TICK) {
            ConstructionOperation candidate = candidates.get(cursor++);
            if (!level.isLoaded(candidate.pos()) || failures.isCoolingDown(candidate.pos(), now)) continue;
            BlockState current = level.getBlockState(candidate.pos());
            if (current.equals(candidate.targetState())) continue;
            Work work = new Work(candidate, current, null, foundation, false);
            if (work.breaking() && !NpcBlockInteraction.canDemolish(level, candidate.pos(), current)) {
                markFailed(candidate.pos(), now, ActionResult.Failure.FORBIDDEN);
                continue;
            }
            if (!work.supported(level)) continue;
            if (!work.breaking() && !NpcBlockInteraction.hasPlacementSupport(
                    level, candidate.pos(), candidate.targetState())) continue;
            if (work.canOperate(npc)) return work;
            if (searches >= searchBudget) {
                cursor--; // 下个 tick 继续当前候选，不能因预算耗尽丢掉它。
                return null;
            }
            searches++;
            var search = work.breaking() ? NpcBlockInteraction.searchWorkPosition(npc, candidate.pos())
                    : NpcBlockInteraction.searchPlacementPosition(npc, candidate.pos(), candidate.targetState());
            var stand = search.stand();
            if (stand.isPresent()) return new Work(candidate, current, stand.get(), foundation, false);
            if (allowFallback && work.allowsFallback()) {
                Work remote = work.withFallback();
                // 没有视线站位时，先尝试在当前高度靠近；失败由执行器切换为隔空放置。
                BlockPos nearby = remote.approached(npc) ? null
                        : new BlockPos(candidate.pos().getX(), npc.blockPosition().getY(), candidate.pos().getZ());
                return new Work(candidate, current, nearby, foundation, true);
            }
            if (search.navigationFailed()) markNavigationFailed(candidate);
            markFailed(candidate.pos(), now, ActionResult.Failure.UNREACHABLE);
        }
        return null;
    }

    boolean exhausted() { return cursor >= candidates.size(); }
    BlockPos findPillarBase(NpcEntity npc, int maxRise, Predicate<BlockPos> allowed) {
        if (navigationBlocked.isEmpty()) return null;
        return ConstructionAccessPlanner.findPillarBase(npc, List.copyOf(navigationBlocked.values()), maxRise, allowed);
    }
    void markNavigationFailed(ConstructionOperation operation) {
        // 建屋放置已有隔空兜底；只为必须实际到达的操作保留垫高候选。
        if (!foundation && operation.action() == ConstructionOperation.Action.PLACE) return;
        navigationBlocked.putIfAbsent(operation.pos(), operation);
    }
    void accessChanged(long now) {
        // 真正落稳后站位已经改变，只提前重试受影响的寻路失败项。
        for (var operation : navigationBlocked.values()) failures.mark(operation.pos(), now, 0);
        navigationBlocked.clear();
    }
    ActionResult.Failure blockedBy() { return blockedBy; }
    void markFailed(BlockPos pos, long now, ActionResult.Failure reason) {
        blockedBy = reason;
        failures.mark(pos, now, 80);
    }
    private static int priority(ServerLevel level, ConstructionOperation task) {
        if (task.action() == ConstructionOperation.Action.REMOVE) return 0;
        if (task.action() == ConstructionOperation.Action.REPLACE
                && level.getBlockState(task.pos()).getBlock() != task.targetState().getBlock()) return 1;
        if (task.action() == ConstructionOperation.Action.PLACE) return attachment(task.targetState()) ? 3 : 2;
        return 4;
    }

    private static boolean attachment(BlockState state) {
        var block = state.getBlock();
        return block instanceof BaseTorchBlock || block instanceof BushBlock
                || block instanceof FaceAttachedHorizontalDirectionalBlock || block instanceof LadderBlock
                || block instanceof VineBlock || block instanceof CarpetBlock || block instanceof LanternBlock;
    }
}
