package io.github.dabaihenhaochi.wayside.entity.task;

import io.github.dabaihenhaochi.wayside.Wayside;
import io.github.dabaihenhaochi.wayside.entity.NpcEntity;
import io.github.dabaihenhaochi.wayside.entity.action.ActionResult;
import io.github.dabaihenhaochi.wayside.entity.action.MoveToAction;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

import java.util.Objects;

/**
 * Controls one foreground task. Manual movement is a temporary override;
 * it does not erase the task or silently unpause a paused task.
 */
public final class NpcTaskRunner {
    public enum State {
        RUNNING, PAUSED, COMPLETED, FAILED, CANCELED
    }

    private final NpcEntity npc;
    private NpcTask task;
    private State state = State.RUNNING;
    private ActionResult lastResult = ActionResult.running();
    private MoveToAction manualMove;

    public NpcTaskRunner(NpcEntity npc) {
        this.npc = npc;
        this.task = new GatherAndCraftPlanksTask();
    }

    /** Replace the objective, discarding its previous unfinished work. */
    public void setTask(NpcTask next) {
        if (state == State.RUNNING && manualMove == null) {
            task.pause(npc);
        }
        cancelManualMove();
        task = Objects.requireNonNull(next);
        state = State.RUNNING;
        lastResult = ActionResult.running();
    }

    /** Temporarily release the active action, keeping the task's progress. */
    public void pause() {
        cancelManualMove();
        if (state == State.RUNNING) {
            task.pause(npc);
            state = State.PAUSED;
        }
    }

    public void resume() {
        if (state == State.PAUSED) {
            state = State.RUNNING;
        }
    }

    /** A failed task is not retried without an explicit request. */
    public void retry() {
        if (state == State.FAILED) {
            state = State.RUNNING;
            lastResult = ActionResult.running();
        }
    }

    /** Cancel without pretending that work has completed successfully. */
    public void cancel() {
        cancelManualMove();
        if (state == State.RUNNING) {
            task.pause(npc);
        }
        if (state == State.RUNNING || state == State.PAUSED || state == State.FAILED) {
            state = State.CANCELED;
        }
    }

    public State state() { return state; }
    public ActionResult lastResult() { return lastResult; }

    public boolean hasWork() {
        return manualMove != null || state == State.RUNNING;
    }

    public void tick() {
        if (manualMove != null) {
            ActionResult result = manualMove.tick(npc);
            if (!result.isRunning()) {
                cancelManualMove();
            }
            return;
        }
        if (state != State.RUNNING) {
            return;
        }
        ActionResult result = task.tick(npc);
        if (result.isRunning()) {
            return;
        }
        task.pause(npc);
        lastResult = result;
        state = result.isSuccess() ? State.COMPLETED : State.FAILED;
    }

    /** Interrupt ongoing work to visit a position, then resume if still running. */
    public void temporarilyMoveTo(BlockPos pos) {
        Objects.requireNonNull(pos);
        if (state == State.RUNNING && manualMove == null) {
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

    /** Read-only diagnostics; neither of these methods edits the active task. */
    public BlockPos activeMoveTarget() {
        if (manualMove != null) return manualMove.targetPosition();
        return state == State.RUNNING ? task.activeMoveTarget() : null;
    }

    public BlockPos activeBlockTarget() {
        return manualMove == null && state == State.RUNNING
                ? task.activeBlockTarget() : null;
    }

    public boolean isBlockOnCooldown(BlockPos pos) {
        return task.isBlockOnCooldown(pos, npc.level().getGameTime());
    }

    public String description() {
        return task.typeId() + " / " + task.stageName() + " / " + state
                + (state == State.FAILED ? " (" + lastResult.failure() + ")" : "");
    }

    public CompoundTag save() {
        CompoundTag result = new CompoundTag();
        result.putString("Type", task.typeId());
        result.put("Data", task.save());
        result.putString("State", state.name());
        if (state == State.FAILED) {
            result.putString("LastFailure", lastResult.failure().name());
        }
        // Old versions used Terminal; keep it readable by older code.
        result.putBoolean("Terminal", state == State.COMPLETED
                || state == State.FAILED || state == State.CANCELED);
        // A manual move is transient: it is not continued after a world reload.
        return result;
    }

    public void load(CompoundTag entityTag) {
        cancelManualMove();
        if (state == State.RUNNING) {
            task.pause(npc);
        }
        task = new GatherAndCraftPlanksTask();
        state = State.RUNNING;
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
                if (saved.contains("State", Tag.TAG_STRING)) {
                    try {
                        state = State.valueOf(saved.getString("State"));
                    } catch (IllegalArgumentException exception) {
                        Wayside.LOGGER.warn("[Wayside] Invalid NPC task state: {}", saved.getString("State"));
                    }
                } else if (saved.getBoolean("Terminal")) {
                    state = State.COMPLETED;
                }
                if (state == State.FAILED) {
                    ActionResult.Failure failure = ActionResult.Failure.UNKNOWN;
                    try {
                        failure = ActionResult.Failure.valueOf(saved.getString("LastFailure"));
                    } catch (IllegalArgumentException ignored) {
                        // Preserve the failed state even if its reason is unknown.
                    }
                    lastResult = ActionResult.failed(
                            failure == ActionResult.Failure.NONE
                                    ? ActionResult.Failure.UNKNOWN : failure);
                } else if (state == State.COMPLETED) {
                    lastResult = ActionResult.success();
                }
                return;
            }
            Wayside.LOGGER.warn("[Wayside] Unknown NPC task {}, defaulting to wood task", id);
        }

        // Recover save data from v0.0.6, before the task runner existed.
        if (entityTag.contains("WaysideTaskStage", Tag.TAG_STRING)) {
            CompoundTag legacy = new CompoundTag();
            legacy.putString("Stage", entityTag.getString("WaysideTaskStage"));
            task.load(legacy);
            if ("COMPLETED".equals(entityTag.getString("WaysideTaskStage"))) {
                state = State.COMPLETED;
                lastResult = ActionResult.success();
            }
        }
    }
}
