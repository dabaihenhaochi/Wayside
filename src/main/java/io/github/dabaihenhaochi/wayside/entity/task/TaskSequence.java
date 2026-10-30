package io.github.dabaihenhaochi.wayside.entity.task;

import io.github.dabaihenhaochi.wayside.entity.NpcEntity;
import io.github.dabaihenhaochi.wayside.entity.action.ActionResult;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.stream.IntStream;

/** 顺序执行可嵌套的子任务。每 tick 只推进一步，后续任务可在轮到时才创建。 */
public class TaskSequence extends CompositeTask {
    public record Step(String name, Supplier<? extends NpcTask> factory) {
        public Step {
            if (Objects.requireNonNull(name).isBlank()) throw new IllegalArgumentException("Empty step name");
            Objects.requireNonNull(factory);
        }
    }

    private final String id;
    private final List<Step> steps;
    private final NpcTask[] tasks;
    private CompoundTag savedSteps = new CompoundTag();
    private int current;

    public TaskSequence(String id, List<? extends NpcTask> tasks) {
        this(id, IntStream.range(0, tasks.size()).mapToObj(i -> {
            NpcTask task = Objects.requireNonNull(tasks.get(i));
            return step(Integer.toString(i), () -> task);
        }).toArray(Step[]::new));
        for (int i = 0; i < tasks.size(); i++) this.tasks[i] = tasks.get(i);
    }

    public TaskSequence(String id, Step... steps) {
        this.id = Objects.requireNonNull(id);
        this.steps = List.of(steps);
        if (steps.length == 0) throw new IllegalArgumentException("Empty task sequence");
        var names = new HashSet<String>();
        for (Step step : steps) {
            if (!names.add(step.name())) throw new IllegalArgumentException("Duplicate step: " + step.name());
        }
        tasks = new NpcTask[steps.length];
    }

    public static Step step(String name, Supplier<? extends NpcTask> factory) {
        return new Step(name, factory);
    }

    @Override protected NpcTask activeTask() { return current < tasks.length ? tasks[current] : null; }
    @Override public String typeId() { return id; }
    public String currentStepName() { return current < steps.size() ? steps.get(current).name() : "COMPLETED"; }
    @Override public String stageName() {
        NpcTask task = activeTask();
        return task == null ? currentStepName() : currentStepName() + " / " + task.stageName();
    }

    // 子任务成功后进入下一步，失败则交给上层处理。
    @Override public ActionResult tick(NpcEntity npc) {
        if (current == steps.size()) return ActionResult.success();
        if (tasks[current] == null) {
            tasks[current] = Objects.requireNonNull(steps.get(current).factory().get(), "Step factory returned null");
            String name = currentStepName();
            if (savedSteps.contains(name, Tag.TAG_COMPOUND)) {
                tasks[current].load(savedSteps.getCompound(name));
                savedSteps.remove(name);
            }
        }
        NpcTask task = tasks[current];
        ActionResult result = task.tick(npc);
        if (result.isRunning()) return result;
        task.pause(npc);
        if (result.isFailed()) return result;
        current++;
        return current == steps.size() ? ActionResult.success() : ActionResult.running();
    }

    // 不创建尚未执行的任务。读档后尚未实例化的子任务数据也要原样保留。
    @Override public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("Current", current);
        CompoundTag data = savedSteps.copy();
        for (int i = 0; i < steps.size(); i++) {
            if (tasks[i] != null) data.put(steps.get(i).name(), tasks[i].save());
        }
        tag.put("Steps", data);
        return tag;
    }

    // 执行到该步骤时再用工厂恢复，以便工厂读取前序任务的结果。
    @Override public void load(CompoundTag tag) {
        current = Math.max(0, Math.min(steps.size(), tag.getInt("Current")));
        savedSteps = tag.getCompound("Steps").copy();
        Arrays.fill(tasks, null);
    }
}
