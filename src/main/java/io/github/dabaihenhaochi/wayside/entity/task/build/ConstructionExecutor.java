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

/** 建筑与地形共用的动作循环。只消费调用者提供的工作计划，不认识蓝图、选址或初始物资。 */
final class ConstructionExecutor {
    private static final long STALL_TICKS = 1200;
    private enum Stage { PLANNING, MOVING, BREAKING, PLACING, COMPLETED }
    private final ConstructionWorkSelector selector;
    private final int floorY;
    private ConstructionWorkSelector.Work work;
    private NpcAction action;
    private Stage stage = Stage.PLANNING;
    private boolean dirty = true;
    private long lastProgress = -1;
    private long nextPlan;

    ConstructionExecutor(boolean foundation) { this(foundation, Integer.MIN_VALUE); }
    ConstructionExecutor(boolean foundation, int floorY) {
        selector = new ConstructionWorkSelector(foundation);
        this.floorY = floorY;
    }
    String stageName() { return stage.name(); }
    BlockPos activeBlockTarget() { return work == null ? null : work.operation().pos(); }
    BlockPos activeMoveTarget() { return stage == Stage.MOVING && work != null ? work.stand() : null; }

    ActionResult tick(NpcEntity npc, Supplier<ConstructionPlanner.Plan> planner) {
        if (!(npc.level() instanceof ServerLevel level)) return ActionResult.failed(ActionResult.Failure.INVALID_TARGET);
        long now = level.getGameTime();
        if (lastProgress < 0) lastProgress = now;
        if (now - lastProgress > STALL_TICKS) return ActionResult.failed(selector.blockedBy());
        if (action != null) return advanceAction(npc, level, now);
        if (!npc.onGround() || now < nextPlan) return ActionResult.running();
        if (dirty) {
            ActionResult result = preparePlan(npc, planner.get());
            if (!result.isRunning()) return result;
        }
        work = selector.next(npc);
        if (work != null) {
            if (work.stand() == null) beginWork(npc);
            else {
                stage = Stage.MOVING;
                var selected = work;
                action = new MoveToAction(work.stand(), 1.0, selected::canOperate);
            }
        } else if (selector.exhausted()) {
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
        selector.prepare(plan.tasks(), npc, floorY);
        dirty = false;
        return ActionResult.running();
    }

    private ActionResult advanceAction(NpcEntity npc, ServerLevel level, long now) {
        if (!level.isLoaded(work.operation().pos())) return ActionResult.failed(ActionResult.Failure.INVALID_TARGET);
        if (!work.matches(level)) { clearWork(npc); return ActionResult.running(); }
        ActionResult result = action.tick(npc);
        if (result.isRunning()) return result;
        Stage finished = stage;
        var attempted = work;
        action.stop(npc);
        action = null;
        if (result.isFailed()) {
            clearWork(npc);
            if (result.failure() == ActionResult.Failure.MISSING_INGREDIENT
                    || result.failure() == ActionResult.Failure.FORBIDDEN) return result;
            selector.markFailed(attempted.operation().pos(), now, result.failure());
        } else if (finished == Stage.MOVING) beginWork(npc);
        else {
            lastProgress = now;
            clearWork(npc); // 拆除后也重新检查现场，重新选择放置站位。
        }
        return ActionResult.running();
    }

    private void beginWork(NpcEntity npc) {
        npc.getNavigation().stop();
        var selected = work;
        BlockPos pos = selected.operation().pos();
        if (selected.breaking()) {
            stage = Stage.BREAKING;
            action = new BreakBlockAction(pos, state -> state.equals(selected.expectedState())
                    && NpcBlockInteraction.canDemolish(npc.level(), pos, state));
        } else {
            stage = Stage.PLACING;
            action = new PlaceBlockAction(pos, selected.operation().targetState());
        }
    }
    private void clearWork(NpcEntity npc) {
        if (action != null) action.stop(npc);
        action = null;
        work = null;
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
