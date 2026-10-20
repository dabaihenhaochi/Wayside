package io.github.dabaihenhaochi.wayside.entity.task;

import io.github.dabaihenhaochi.wayside.entity.NpcEntity;
import io.github.dabaihenhaochi.wayside.entity.action.ActionResult;
import io.github.dabaihenhaochi.wayside.entity.action.MoveToAction;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import java.util.Objects;

/** Second consumer of generic movement: visit a position without harvesting. */
public final class VisitPositionTask implements NpcTask {
    public static final String ID = "wayside:visit_position";
    private BlockPos destination;
    private MoveToAction action;

    public VisitPositionTask() { this(BlockPos.ZERO); } // For save/load factory.
    public VisitPositionTask(BlockPos destination) {
        this.destination = Objects.requireNonNull(destination).immutable();
    }

    @Override public String typeId() { return ID; }
    @Override public String stageName() { return "TRAVELING"; }
    @Override public ActionResult tick(NpcEntity npc) {
        if (action == null) action = new MoveToAction(destination, 1.0);
        return action.tick(npc);
    }
    @Override public void pause(NpcEntity npc) {
        if (action != null) {
            action.stop(npc);
            action = null;
        }
    }
    @Override public BlockPos activeMoveTarget() { return destination; }

    @Override public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putIntArray("Destination",
                new int[]{destination.getX(), destination.getY(), destination.getZ()});
        return tag;
    }
    @Override public void load(CompoundTag tag) {
        int[] p = tag.getIntArray("Destination");
        if (p.length == 3) destination = new BlockPos(p[0], p[1], p[2]);
    }
}
