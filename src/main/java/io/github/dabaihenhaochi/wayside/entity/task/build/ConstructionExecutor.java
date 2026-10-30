package io.github.dabaihenhaochi.wayside.entity.task.build;

import io.github.dabaihenhaochi.wayside.building.BuildingMaterialCalculator;
import io.github.dabaihenhaochi.wayside.building.ConstructionPlanner;
import io.github.dabaihenhaochi.wayside.entity.NpcEntity;
import io.github.dabaihenhaochi.wayside.entity.action.ActionResult;
import io.github.dabaihenhaochi.wayside.entity.action.BreakBlockAction;
import io.github.dabaihenhaochi.wayside.entity.action.MoveToAction;
import io.github.dabaihenhaochi.wayside.entity.action.NpcAction;
import io.github.dabaihenhaochi.wayside.entity.action.PlaceBlockAction;
import io.github.dabaihenhaochi.wayside.entity.inventory.InventoryOps;
import io.github.dabaihenhaochi.wayside.entity.ai.NpcBlockInteraction;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import java.util.function.Supplier;
import java.util.function.Predicate;
import net.minecraft.nbt.CompoundTag;

/** 建筑与地形共用的动作循环。只消费调用者提供的工作计划，不认识蓝图、选址或初始物资。 */
final class ConstructionExecutor {
    private static final long STALL_TICKS = 1200;
    private static final long PLACEMENT_MOVE_TICKS = 60;
    private enum Stage { PLANNING, MOVING, BREAKING, PLACING, RAISING, COMPLETED }
    private final ConstructionWorkSelector selector;
    private ConstructionWorkSelector.Work work;
    private NpcAction action;
    private Stage stage = Stage.PLANNING;
    private boolean dirty = true;
    private long lastProgress = -1;
    private long nextPlan;
    private long moveStarted;
    private final ConstructionRecovery recovery = new ConstructionRecovery();

    ConstructionExecutor(boolean foundation) {
        selector = new ConstructionWorkSelector(foundation);
    }
    String stageName() { return stage.name(); }
    BlockPos activeBlockTarget() {
        if (stage == Stage.RAISING) return recovery.activeTarget();
        if (work == null) return null;
        return work.operation().pos();
    }
    BlockPos activeMoveTarget() {
        if (stage == Stage.RAISING) return recovery.activeTarget();
        if (stage == Stage.MOVING && work != null) return work.stand();
        return null;
    }
    CompoundTag save() { return recovery.save(); }
    void load(CompoundTag tag) { recovery.load(tag); }

    ActionResult tick(NpcEntity npc, Supplier<ConstructionPlanner.Plan> planner, Predicate<BlockPos> accessAllowed) {
        if (!(npc.level() instanceof ServerLevel level)) return ActionResult.failed(ActionResult.Failure.INVALID_TARGET);
        long now = level.getGameTime();
        if (lastProgress < 0) lastProgress = now;
        if (now - lastProgress > STALL_TICKS) return ActionResult.failed(selector.blockedBy());
        if (action != null) {
            if (stage == Stage.RAISING) return advancePillar(npc, now);
            return advanceAction(npc, level, accessAllowed, now);
        }
        if (!npc.onGround() || now < nextPlan) return ActionResult.running();
        if (dirty) {
            ActionResult result = preparePlan(npc, planner.get());
            if (!result.isRunning()) return result;
        }
        work = selector.next(npc, true);
        if (work != null) {
            if (work.stand() == null) beginWork(npc);
            else {
                stage = Stage.MOVING;
                moveStarted = now;
                var selected = work;
                action = new MoveToAction(work.stand(), 1.0, selected::approached);
            }
        } else if (selector.exhausted()) {
            if (beginRecovery(npc, accessAllowed, now)) return ActionResult.running();
            dirty = true;
            nextPlan = now + 20;
        }
        return ActionResult.running();
    }

    private ActionResult preparePlan(NpcEntity npc, ConstructionPlanner.Plan plan) {
        if (plan.isComplete()) { stage = Stage.COMPLETED; return ActionResult.success(); }
        var materials = BuildingMaterialCalculator.calculate(plan);
        if (!materials.canSupply()) return ActionResult.failed(ActionResult.Failure.INVALID_TARGET);
        for (var requirement : materials.requiredItems().entrySet()) {
            if (InventoryOps.count(npc.getInventory(), requirement.getKey()) < requirement.getValue()) {
                return ActionResult.failed(ActionResult.Failure.MISSING_INGREDIENT);
            }
        }
        recovery.reserve(materials);
        selector.prepare(plan.tasks(), npc);
        dirty = false;
        return ActionResult.running();
    }

    private boolean beginRecovery(NpcEntity npc, Predicate<BlockPos> accessAllowed, long now) {
        action = recovery.tryStart(npc, selector, accessAllowed, now);
        if (action == null) return false;
        npc.getNavigation().stop();
        stage = Stage.RAISING;
        return true;
    }

    private ActionResult advanceAction(NpcEntity npc, ServerLevel level, Predicate<BlockPos> accessAllowed, long now) {
        if (!level.isLoaded(work.operation().pos())) return ActionResult.failed(ActionResult.Failure.INVALID_TARGET);
        if (!work.matches(level)) { clearWork(npc); return ActionResult.running(); }
        ActionResult result = stage == Stage.MOVING && work.allowsFallback()
                && now - moveStarted >= PLACEMENT_MOVE_TICKS
                ? ActionResult.failed(ActionResult.Failure.TIMED_OUT) : action.tick(npc);
        if (result.isRunning()) return result;
        Stage finished = stage;
        var attempted = work;
        action.stop(npc);
        action = null;
        if (result.isFailed()) {
            // 只对建筑放置的几何/移动失败兜底；支撑、空间、事件及缺料失败不能绕过。
            if (work.allowsFallback() && (finished == Stage.MOVING
                    && (result.failure() == ActionResult.Failure.UNREACHABLE
                        || result.failure() == ActionResult.Failure.TIMED_OUT)
                    || finished == Stage.PLACING && !work.remote()
                        && result.failure() == ActionResult.Failure.UNREACHABLE)) {
                work = work.withFallback();
                beginWork(npc);
                return ActionResult.running();
            }
            clearWork(npc);
            if (result.failure() == ActionResult.Failure.MISSING_INGREDIENT
                    || result.failure() == ActionResult.Failure.FORBIDDEN) return result;
            selector.markFailed(attempted.operation().pos(), now, result.failure());
            if (finished == Stage.MOVING && (result.failure() == ActionResult.Failure.UNREACHABLE
                    || result.failure() == ActionResult.Failure.TIMED_OUT)) {
                selector.markNavigationFailed(attempted.operation());
                beginRecovery(npc, accessAllowed, now);
            }
        } else if (finished == Stage.MOVING) beginWork(npc);
        else {
            recovery.workCompleted();
            lastProgress = now;
            clearWork(npc); // 拆除后也重新检查现场，重新选择放置站位。
        }
        return ActionResult.running();
    }

    private ActionResult advancePillar(NpcEntity npc, long now) {
        ActionResult result = action.tick(npc);
        if (result.isRunning()) return result;
        recovery.finished(result, now);
        if (result.isSuccess()) selector.accessChanged(now);
        clearWork(npc);
        if (result.isSuccess()) lastProgress = now;
        else if (result.failure() == ActionResult.Failure.FORBIDDEN) return result;
        return ActionResult.running();
    }

    private void beginWork(NpcEntity npc) {
        npc.getNavigation().stop();
        // 状态标记必须与本次实际动作一致，否则恢复普通放置后再次失去视线无法立即兜底。
        if (work.remote() && work.canOperate(npc)) work = work.withNormalPlacement();
        var selected = work;
        BlockPos pos = selected.operation().pos();
        if (selected.breaking()) {
            stage = Stage.BREAKING;
            action = new BreakBlockAction(pos, state -> state.equals(selected.expectedState())
                    && NpcBlockInteraction.canDemolish(npc.level(), pos, state));
        } else {
            stage = Stage.PLACING;
            // 寻路途中视线恢复时，仍优先走正常放置入口。
            action = selected.remote()
                    ? PlaceBlockAction.constructionFallback(pos, selected.operation().targetState())
                    : new PlaceBlockAction(pos, selected.operation().targetState());
        }
    }
    private void clearWork(NpcEntity npc) {
        if (action != null) action.stop(npc);
        action = null;
        work = null;
        recovery.clearActive();
        dirty = true;
        stage = Stage.PLANNING;
    }
    void pause(NpcEntity npc) {
        boolean completed = stage == Stage.COMPLETED;
        clearWork(npc);
        if (completed) stage = Stage.COMPLETED;
        lastProgress = -1;
        nextPlan = 0;
    }
}
