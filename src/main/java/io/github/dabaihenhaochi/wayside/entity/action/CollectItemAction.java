package io.github.dabaihenhaochi.wayside.entity.action;

import io.github.dabaihenhaochi.wayside.entity.NpcEntity;
import net.minecraft.world.entity.item.ItemEntity;

import java.util.Objects;

/** Approaches one dropped item and waits for the NPC's inventory pickup. */
public final class CollectItemAction implements NpcAction {
    private static final long TIMEOUT_TICKS = 200;
    private final ItemEntity item;
    private final MoveToAction movement;
    private long startedAt = -1;

    public CollectItemAction(ItemEntity item) {
        this.item = Objects.requireNonNull(item);
        this.movement = new MoveToAction(item, 1.0, npc ->
                npc.getBoundingBox().inflate(0.5).intersects(item.getBoundingBox()));
    }

    @Override
    public ActionResult tick(NpcEntity npc) {
        if (!item.isAlive() || item.getItem().isEmpty()) {
            return ActionResult.success();
        }
        if (!npc.wantsItem(item.getItem())) {
            return ActionResult.failed(ActionResult.Failure.INVALID_TARGET);
        }
        if (!npc.getInventory().canAddItem(item.getItem())) {
            return ActionResult.failed(ActionResult.Failure.NO_SPACE);
        }

        long now = npc.level().getGameTime();
        if (startedAt < 0) {
            startedAt = now;
        }
        if (now - startedAt >= TIMEOUT_TICKS) {
            return ActionResult.failed(ActionResult.Failure.TIMED_OUT);
        }

        if (npc.distanceToSqr(item) < 9.0
                && Math.abs(npc.getY() - item.getY()) < 1.0
                && npc.hasLineOfSight(item)) {
            npc.getMoveControl().setWantedPosition(
                    item.getX(), item.getY(), item.getZ(), 1.0);
        }

        ActionResult moveResult = movement.tick(npc);
        return moveResult.isFailed() ? moveResult : ActionResult.running();
    }

    @Override
    public void stop(NpcEntity npc) {
        movement.stop(npc);
    }
}
