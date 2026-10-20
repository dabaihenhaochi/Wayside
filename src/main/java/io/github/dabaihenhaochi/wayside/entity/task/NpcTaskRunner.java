package io.github.dabaihenhaochi.wayside.entity.task;

import io.github.dabaihenhaochi.wayside.Wayside;
import io.github.dabaihenhaochi.wayside.entity.NpcEntity;
import io.github.dabaihenhaochi.wayside.entity.action.ActionResult;
import io.github.dabaihenhaochi.wayside.entity.action.MoveToAction;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;

import java.util.Objects;

/** Single foreground task and an optional temporary manual movement override. */
public final class NpcTaskRunner {
    private final NpcEntity npc;
    private NpcTask task;
    private boolean terminal;
    private ActionResult lastResult = ActionResult.running();
    private MoveToAction manualMove;

    public NpcTaskRunner(NpcEntity npc) {
        this.npc = npc;
        this.task = new GatherAndCraftPlanksTask();
    }

    public void setTask(NpcTask next) {
        if (task != null) {
            task.pause(npc);
        }
        cancelManualMove();
        task = Objects.requireNonNull(next);
        terminal = false;
        lastResult = ActionResult.running();
    }

    public boolean hasWork() {
        return manualMove != null || (task != null && !terminal);
    }

    public void tick() {
        if (manualMove != null) {
            ActionResult result = manualMove.tick(npc);
            if (!result.isRunning()) {
                cancelManualMove();
            }
            return;
        }
        if (task == null || terminal) {
            return;
        }
        ActionResult result = task.tick(npc);
        if (!result.isRunning()) {
            task.pause(npc);
            terminal = true;
            lastResult = result;
        }
    }

    public void temporarilyMoveTo(BlockPos pos) {
        if (task != null && !terminal) {
            task.pause(npc);
        }
        cancelManualMove();
        manualMove = new MoveToAction(pos, 1.0);
    }

    public void cancelManualMove() {
        if (manualMove != null) {
            manualMove.stop(npc);
            manualMove = null;
        }
    }

    public BlockPos getMoveTarget() {
        return manualMove != null ? manualMove.targetPosition()
                : task != null && !terminal ? task.activeMoveTarget() : null;
    }

    public BlockPos getBlockTarget() {
        return task != null && !terminal && manualMove == null
                ? task.activeBlockTarget() : null;
    }

    public void abandonWorkTarget() {
        if (task != null && !terminal) {
            task.abandonWorkTarget(npc);
        }
    }

    public void markBlockFailed(BlockPos pos) {
        if (task != null && !terminal) {
            task.markBlockFailed(pos, npc.level().getGameTime());
        }
    }

    public boolean isBlockOnCooldown(BlockPos pos) {
        return task != null
                && task.isBlockOnCooldown(pos, npc.level().getGameTime());
    }

    public boolean wantsItem(ItemStack stack) {
        return task != null && !terminal && manualMove == null && task.wantsItem(stack);
    }

    public String description() {
        return task == null ? "none"
                : task.typeId() + " / " + task.stageName()
                + (terminal ? " / " + lastResult.status() : "");
    }

    public CompoundTag save() {
        CompoundTag result = new CompoundTag();
        if (task != null) {
            result.putString("Type", task.typeId());
            result.put("Data", task.save());
            result.putBoolean("Terminal", terminal);
        }
        return result;
    }

    public void load(CompoundTag entityTag) {
        cancelManualMove();
        if (task != null) {
            task.pause(npc);
        }
        task = new GatherAndCraftPlanksTask();
        terminal = false;
        lastResult = ActionResult.running();

        if (entityTag.contains("WaysideTask", Tag.TAG_COMPOUND)) {
            CompoundTag saved = entityTag.getCompound("WaysideTask");
            String id = saved.getString("Type");
            var loaded = NpcTaskTypes.create(id);
            if (loaded.isPresent()) {
                task = loaded.get();
                if (saved.contains("Data", Tag.TAG_COMPOUND)) {
                    task.load(saved.getCompound("Data"));
                }
                terminal = saved.getBoolean("Terminal");
                return;
            }
            Wayside.LOGGER.warn("[Wayside] Unknown NPC task {}, defaulting to wood task", id);
        }

        // Load existing v0.0.6 entities without resetting their wood-processing stage.
        if (entityTag.contains("WaysideTaskStage", Tag.TAG_STRING)) {
            CompoundTag legacy = new CompoundTag();
            legacy.putString("Stage", entityTag.getString("WaysideTaskStage"));
            task.load(legacy);
            terminal = "COMPLETED".equals(entityTag.getString("WaysideTaskStage"));
        }
    }
}
