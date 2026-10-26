package io.github.dabaihenhaochi.wayside.entity.task;

import io.github.dabaihenhaochi.wayside.entity.NpcEntity;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;

// 将服务端任务更新接入原版目标系统，占用移动和注视控制。
public final class NpcTaskExecutionGoal extends Goal {
    private final NpcEntity npc;

    public NpcTaskExecutionGoal(NpcEntity npc) {
        this.npc = npc;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        return !npc.level().isClientSide && npc.getTaskRunner().hasWork();
    }

    @Override
    public boolean canContinueToUse() {
        return canUse();
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        npc.getTaskRunner().tick();
    }
}
