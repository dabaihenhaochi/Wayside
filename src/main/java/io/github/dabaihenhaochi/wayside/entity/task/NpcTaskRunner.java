package io.github.dabaihenhaochi.wayside.entity.task;

import io.github.dabaihenhaochi.wayside.Wayside;
import io.github.dabaihenhaochi.wayside.entity.NpcEntity;
import io.github.dabaihenhaochi.wayside.entity.action.ActionResult;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

import java.util.Objects;
import java.util.function.Supplier;

// 管理正式任务及其状态；临时任务可打断执行，但不丢失原任务进度。
public final class NpcTaskRunner {
    public enum State {
        RUNNING, PAUSED, COMPLETED, FAILED, CANCELED
    }

    private final NpcEntity npc;
    private final Supplier<? extends NpcTask> defaultTaskFactory;
    private NpcTask task;
    private State state = State.RUNNING;
    private ActionResult lastResult = ActionResult.running();
    private NpcTask temporaryTask;

    public NpcTaskRunner(NpcEntity npc, Supplier<? extends NpcTask> defaultTaskFactory) {
        this.npc = Objects.requireNonNull(npc);
        this.defaultTaskFactory = Objects.requireNonNull(defaultTaskFactory);
        this.task = createDefaultTask();
    }

    private NpcTask createDefaultTask() {
        return Objects.requireNonNull(defaultTaskFactory.get(), "Default task factory returned null");
    }

    // 清理旧动作并替换任务，从运行状态开始。
    public void setTask(NpcTask next) {
        if (state == State.RUNNING && temporaryTask == null) {
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

    // 优先返回临时任务；正式任务只有在运行状态才参与执行。
    private NpcTask activeTask() {
        if (temporaryTask != null) {
            return temporaryTask;
        }
        return state == State.RUNNING ? task : null;
    }

    public boolean hasWork() {
        return activeTask() != null;
    }

    // 统一推进当前任务；临时任务结束后恢复原任务，正式任务结束时记录结果。
    public void tick() {
        NpcTask current = activeTask();
        if (current == null) {
            return;
        }

        ActionResult result = current.tick(npc);
        if (result.isRunning()) {
            return;
        }

        current.pause(npc);
        if (current == temporaryTask) {
            temporaryTask = null;
            return;
        }

        lastResult = result;
        state = result.isSuccess() ? State.COMPLETED : State.FAILED;
    }

    // 用移动任务临时接管执行；只在首次打断时暂停原任务。
    public void temporarilyMoveTo(BlockPos pos) {
        Objects.requireNonNull(pos);
        if (state == State.RUNNING && temporaryTask == null) {
            task.pause(npc);
        }
        cancelManualMove();
        temporaryTask = new VisitPositionTask(pos);
    }

    // 清除临时任务并停止其动作，不修改正式任务的状态和进度。
    public void cancelManualMove() {
        if (temporaryTask != null) {
            temporaryTask.pause(npc);
            temporaryTask = null;
        }
    }

    // 查询当前执行任务的目标，不推进任务。
    public BlockPos activeMoveTarget() {
        NpcTask current = activeTask();
        return current == null ? null : current.activeMoveTarget();
    }

    public BlockPos activeBlockTarget() {
        NpcTask current = activeTask();
        return current == null ? null : current.activeBlockTarget();
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

    // 按任务类型恢复；未知类型采用调用者指定的默认任务。
    public void load(CompoundTag entityTag) {
        cancelManualMove();
        if (state == State.RUNNING) {
            task.pause(npc);
        }
        task = createDefaultTask();
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
            Wayside.LOGGER.warn("[Wayside] Unknown NPC task {}, using configured default task", id);
        }

        // 具体旧任务的转换交给类型登记处，运行器只恢复生命周期状态。
        var legacy = NpcTaskTypes.restoreLegacy(entityTag);
        if (legacy.isPresent()) {
            task = legacy.get();
            if ("COMPLETED".equals(entityTag.getString("WaysideTaskStage"))) {
                state = State.COMPLETED;
                lastResult = ActionResult.success();
            }
        }
    }
}
