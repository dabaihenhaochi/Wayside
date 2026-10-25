package io.github.dabaihenhaochi.wayside.entity.task;

import io.github.dabaihenhaochi.wayside.Wayside;
import io.github.dabaihenhaochi.wayside.entity.NpcEntity;
import io.github.dabaihenhaochi.wayside.entity.action.ActionResult;
import io.github.dabaihenhaochi.wayside.entity.action.MoveToAction;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

import java.util.Objects;

// 管理当前任务及其状态；手动移动临时接管，不清空任务或解除暂停。
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

    // 清理旧动作并替换任务，从运行状态开始。
    public void setTask(NpcTask next) {
        if (state == State.RUNNING && manualMove == null) {
            task.pause(npc);
        }
        cancelManualMove();
        task = Objects.requireNonNull(next);
        state = State.RUNNING;
        lastResult = ActionResult.running();
    }

    // 取消手动移动并暂停任务，保留任务进度。
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

    // 仅在明确重试时重新运行失败的任务。
    public void retry() {
        if (state == State.FAILED) {
            state = State.RUNNING;
            lastResult = ActionResult.running();
        }
    }

    // 清理当前动作，将未结束的任务标记为取消。
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

    // 优先执行手动移动；正常任务结束后清理动作并记录结果。
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

    // 临时移动结束后，仅当原任务仍为运行状态才继续。
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

    // 诊断信息只反映当前目标，不推进任务。
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

    // 保存任务类型、进度和运行状态，失败时同时保存原因。
    public CompoundTag save() {
        CompoundTag result = new CompoundTag();
        result.putString("Type", task.typeId());
        result.put("Data", task.save());
        result.putString("State", state.name());
        if (state == State.FAILED) {
            result.putString("LastFailure", lastResult.failure().name());
        }
        // 保留旧版 Terminal 字段，供旧代码读取。
        result.putBoolean("Terminal", state == State.COMPLETED
                || state == State.FAILED || state == State.CANCELED);
        // 手动移动属于临时指令，重载世界后不恢复。
        return result;
    }

    // 按任务类型恢复进度和状态，兼容旧存档；未知类型退回默认采木任务。
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
                        // 无法识别原因时仍保留失败状态。
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

        // 兼容尚未引入运行器的 v0.0.6 阶段字段。
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
