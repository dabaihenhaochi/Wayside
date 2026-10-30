package io.github.dabaihenhaochi.wayside.entity.task;

import io.github.dabaihenhaochi.wayside.entity.NpcEntity;
import net.minecraft.core.BlockPos;

/** 父任务只向当前子任务转发暂停和目标查询；业务判断及推进方式由父任务决定。 */
public abstract class CompositeTask implements NpcTask {
    protected abstract NpcTask activeTask();

    @Override public void pause(NpcEntity npc) {
        NpcTask task = activeTask();
        if (task != null) task.pause(npc);
    }

    @Override public BlockPos activeBlockTarget() {
        NpcTask task = activeTask();
        return task == null ? null : task.activeBlockTarget();
    }

    @Override public BlockPos activeMoveTarget() {
        NpcTask task = activeTask();
        return task == null ? null : task.activeMoveTarget();
    }
}
