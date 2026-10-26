package io.github.dabaihenhaochi.wayside.entity.action;

import io.github.dabaihenhaochi.wayside.entity.NpcEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;

import java.util.Objects;
import java.util.function.Predicate;

// 靠近指定掉落物并放入背包，物品筛选条件由任务提供。
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

    // 检查目标、超时和背包空间，拾取后保留装不下的剩余物品。
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
            // 等待原版掉落物的拾取延迟结束。
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

        // 寻路提前停下时，仅在近距离且无遮挡处补上最后一段移动。
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

    // 同时检查碰撞范围、高度差和视线，避免隔墙或跨层拾取。
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
