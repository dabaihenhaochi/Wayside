package io.github.dabaihenhaochi.wayside.entity.task;

import io.github.dabaihenhaochi.wayside.entity.NpcEntity;
import io.github.dabaihenhaochi.wayside.entity.action.ActionResult;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import java.util.List;
import java.util.Objects;

/** Sequentially executes task components without knowing which actions they use. */
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
    @Override public void abandonWorkTarget(NpcEntity npc) {
        if (active() != null) active().abandonWorkTarget(npc);
    }
    @Override public boolean isBlockOnCooldown(BlockPos pos, long now) {
        return active() != null && active().isBlockOnCooldown(pos, now);
    }
    @Override public void markBlockFailed(BlockPos pos, long now) {
        if (active() != null) active().markBlockFailed(pos, now);
    }

    @Override public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("Current", current);
        ListTag list = new ListTag();
        for (NpcTask step : steps) list.add(step.save());
        tag.put("Steps", list);
        return tag;
    }

    @Override public void load(CompoundTag tag) {
        // Legacy v0.0.6 and initial v0.0.7 stored a single stage string.
        if (tag.contains("Stage", Tag.TAG_STRING)) {
            current = switch (tag.getString("Stage")) {
                case "CRAFTING" -> Math.min(1, steps.size());
                case "COMPLETED" -> steps.size();
                default -> 0;
            };
            return;
        }
        current = Math.max(0, Math.min(steps.size(), tag.getInt("Current")));
        if (tag.contains("Steps", Tag.TAG_LIST)) {
            ListTag data = tag.getList("Steps", Tag.TAG_COMPOUND);
            for (int i = 0; i < Math.min(data.size(), steps.size()); i++)
                steps.get(i).load(data.getCompound(i));
        }
    }
}
