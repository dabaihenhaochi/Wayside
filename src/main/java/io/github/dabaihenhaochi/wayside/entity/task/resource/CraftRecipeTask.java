package io.github.dabaihenhaochi.wayside.entity.task.resource;

import io.github.dabaihenhaochi.wayside.entity.NpcEntity;
import io.github.dabaihenhaochi.wayside.entity.action.ActionResult;
import io.github.dabaihenhaochi.wayside.entity.action.CraftRecipeAction;
import io.github.dabaihenhaochi.wayside.entity.action.MoveToAction;
import io.github.dabaihenhaochi.wayside.entity.ai.NpcBlockInteraction;
import io.github.dabaihenhaochi.wayside.entity.task.NpcTask;
import io.github.dabaihenhaochi.wayside.material.MaterialPlan;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;

/** 最末级配方任务：需要时靠近工作台，然后直接调用合成动作。 */
final class CraftRecipeTask implements NpcTask {
    private final boolean needsWorkstation;
    private final CraftRecipeAction crafting;
    private MoveToAction move;

    CraftRecipeTask(MaterialPlan.CraftStep step) {
        needsWorkstation = step.width() > 2 || step.height() > 2;
        crafting = new CraftRecipeAction(step);
    }

    @Override public String typeId() { return "wayside:craft_recipe"; }
    @Override public String stageName() { return move == null ? "CRAFTING" : "MOVING_TO_WORKSTATION"; }
    @Override public BlockPos activeMoveTarget() { return move == null ? null : move.targetPosition(); }

    @Override public ActionResult tick(NpcEntity npc) {
        if (needsWorkstation && !CraftRecipeAction.hasWorkstation(npc)) {
            if (move == null) {
                BlockPos table = BlockPos.findClosestMatch(npc.blockPosition(), 8, 3,
                        pos -> npc.level().hasChunkAt(pos) && npc.level().getBlockState(pos).is(Blocks.CRAFTING_TABLE)).orElse(null);
                if (table == null) return ActionResult.failed(ActionResult.Failure.MISSING_WORKSTATION);
                var stand = NpcBlockInteraction.findWorkPosition(npc, table);
                if (stand.isEmpty()) return ActionResult.failed(ActionResult.Failure.UNREACHABLE);
                move = new MoveToAction(stand.get(), 1.0, CraftRecipeAction::hasWorkstation);
            }
            ActionResult result = move.tick(npc);
            if (!result.isSuccess()) return result;
        }
        stopMoving(npc);
        return crafting.tick(npc);
    }

    private void stopMoving(NpcEntity npc) {
        if (move != null) move.stop(npc);
        move = null;
    }

    @Override public void pause(NpcEntity npc) {
        stopMoving(npc);
        crafting.stop(npc);
    }
}
