package io.github.dabaihenhaochi.wayside.entity.task;

import io.github.dabaihenhaochi.wayside.entity.NpcEntity;
import io.github.dabaihenhaochi.wayside.entity.action.ActionResult;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;

// 长期任务接口，负责安排动作和保留自身进度。
public interface NpcTask {
    String typeId();
    ActionResult tick(NpcEntity npc);
    void pause(NpcEntity npc);
    String stageName();

    default BlockPos activeBlockTarget() { return null; }
    default BlockPos activeMoveTarget() { return null; }
    default CompoundTag save() { return new CompoundTag(); }
    default void load(CompoundTag tag) {}
}
