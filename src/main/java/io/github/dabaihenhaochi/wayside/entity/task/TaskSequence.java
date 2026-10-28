package io.github.dabaihenhaochi.wayside.entity.task;

import io.github.dabaihenhaochi.wayside.entity.NpcEntity;
import io.github.dabaihenhaochi.wayside.entity.action.ActionResult;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import java.util.List;
import java.util.Objects;

// 依次执行子任务，保存当前步骤和各步骤的进度。
public class TaskSequence implements NpcTask {
    private final String id;
    private final List<NpcTask> steps;
    private int current;

    public TaskSequence(String id, List<NpcTask> steps) {
        this.id = Objects.requireNonNull(id);
        this.steps = List.copyOf(steps);
        if (steps.isEmpty()) throw new IllegalArgumentException("Empty task sequence");
    }

    private NpcTask active() { return current < steps.size() ? steps.get(current) : null; }
    @Override public String typeId() { return id; }
    @Override public String stageName() {
        NpcTask task = active();
        return task == null ? "COMPLETED" : task.stageName();
    }

    // 子任务成功后进入下一步，失败则交给上层处理。
    @Override public ActionResult tick(NpcEntity npc) {
        NpcTask task = active();
        if (task == null) return ActionResult.success();
        ActionResult result = task.tick(npc);
        if (result.isRunning()) return result;
        task.pause(npc);
        if (result.isFailed()) return result;
        current++;
        return current == steps.size() ? ActionResult.success() : ActionResult.running();
    }

    @Override public void pause(NpcEntity npc) {
        if (active() != null) active().pause(npc);
    }
    @Override public BlockPos activeBlockTarget() {
        return active() == null ? null : active().activeBlockTarget();
    }
    @Override public BlockPos activeMoveTarget() {
        return active() == null ? null : active().activeMoveTarget();
    }

    // 保存步骤索引及每个子任务的数据。
    @Override public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("Current", current);
        ListTag list = new ListTag();
        for (NpcTask step : steps) list.add(step.save());
        tag.put("Steps", list);
        return tag;
    }

    // 恢复步骤并限制索引范围；具体业务的旧存档转换由对应任务处理。
    @Override public void load(CompoundTag tag) {
        current = Math.max(0, Math.min(steps.size(), tag.getInt("Current")));
        if (tag.contains("Steps", Tag.TAG_LIST)) {
            ListTag data = tag.getList("Steps", Tag.TAG_COMPOUND);
            for (int i = 0; i < Math.min(data.size(), steps.size()); i++)
                steps.get(i).load(data.getCompound(i));
        }
    }
}
