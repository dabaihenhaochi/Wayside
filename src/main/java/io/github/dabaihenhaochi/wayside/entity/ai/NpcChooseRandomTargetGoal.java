package io.github.dabaihenhaochi.wayside.entity.ai;

import io.github.dabaihenhaochi.wayside.entity.NpcEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
import net.minecraft.world.phys.Vec3;

public class NpcChooseRandomTargetGoal extends Goal {

    private final NpcEntity npc;

    public NpcChooseRandomTargetGoal(NpcEntity npc) {
        this.npc = npc;
    }

    @Override
    public boolean canUse() {
        return !this.npc.hasMoveTarget();
    }

    @Override
    public void start() {
        Vec3 target = DefaultRandomPos.getPos(this.npc, 10, 7);

        if (target == null) {
            return;
        }

        BlockPos targetPos = BlockPos.containing(
                target.x,
                target.y,
                target.z
        );

        this.npc.setMoveTarget(targetPos);
    }
}