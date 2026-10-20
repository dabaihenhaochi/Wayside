package io.github.dabaihenhaochi.wayside.entity.action;

import io.github.dabaihenhaochi.wayside.entity.NpcEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;

import java.util.Objects;
import java.util.function.Predicate;

/**
 * Moves to a selected dropped-item entity and transfers it to the NPC inventory.
 * The calling task supplies the allowed item predicate. This action never
 * consults the task runner or decides whether the NPC needs that item.
 */
public final class CollectItemAction implements NpcAction {
    private static final long TIMEOUT_TICKS = 200;
    private final ItemEntity item;
    private final Predicate<ItemStack> acceptedItem;
    private final MoveToAction movement;
    private long startedAt = -1;

    public CollectItemAction(ItemEntity item, Predicate<ItemStack> acceptedItem) {
        this.item = Objects.requireNonNull(item);
        this.acceptedItem = Objects.requireNonNull(acceptedItem);
        this.movement = new MoveToAction(item, 1.0, npc ->
                inPickupReach(npc));
    }

    @Override
    public ActionResult tick(NpcEntity npc) {
        if (!item.isAlive() || item.getItem().isEmpty()
                || !acceptedItem.test(item.getItem())) {
            return ActionResult.failed(ActionResult.Failure.INVALID_TARGET);
        }

        long now = npc.level().getGameTime();
        if (startedAt < 0) {
            startedAt = now;
        }
        if (now - startedAt >= TIMEOUT_TICKS) {
            return ActionResult.failed(ActionResult.Failure.TIMED_OUT);
        }

        ItemStack original = item.getItem();
        if (!npc.getInventory().canAddItem(original)) {
            return ActionResult.failed(ActionResult.Failure.NO_SPACE);
        }

        if (inPickupReach(npc)) {
            // Vanilla dropped items can have a brief pickup delay.
            if (item.hasPickUpDelay()) {
                return ActionResult.running();
            }

            ItemStack remaining = npc.getInventory().addItem(original.copy());
            if (remaining.getCount() >= original.getCount()) {
                return ActionResult.failed(ActionResult.Failure.NO_SPACE);
            }

            if (remaining.isEmpty()) {
                item.discard();
            } else {
                item.setItem(remaining);
            }
            return ActionResult.success();
        }

        // Navigation can stop just short of the item; use movement control to
        // finish the final approach only when close enough and unobstructed.
        if (npc.getNavigation().isDone()
                && npc.distanceToSqr(item) < 9.0
                && Math.abs(npc.getY() - item.getY()) < 1.0
                && npc.hasLineOfSight(item)) {
            npc.getMoveControl().setWantedPosition(
                    item.getX(), item.getY(), item.getZ(), 1.0);
        }

        ActionResult moveResult = movement.tick(npc);
        return moveResult.isFailed() ? moveResult : ActionResult.running();
    }

    private boolean inPickupReach(NpcEntity npc) {
        return npc.getBoundingBox().inflate(0.5).intersects(item.getBoundingBox())
                && Math.abs(npc.getY() - item.getY()) < 1.0
                && npc.hasLineOfSight(item);
    }

    @Override
    public void stop(NpcEntity npc) {
        movement.stop(npc);
    }
}
