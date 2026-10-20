package io.github.dabaihenhaochi.wayside.entity.task;

import io.github.dabaihenhaochi.wayside.entity.NpcEntity;
import io.github.dabaihenhaochi.wayside.entity.action.ActionResult;
import io.github.dabaihenhaochi.wayside.entity.action.BreakBlockAction;
import io.github.dabaihenhaochi.wayside.entity.action.CollectItemAction;
import io.github.dabaihenhaochi.wayside.entity.action.MoveToAction;
import io.github.dabaihenhaochi.wayside.entity.action.NpcAction;
import io.github.dabaihenhaochi.wayside.entity.ai.NpcBlockInteraction;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Comparator;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

/**
 * Searches, walks to a work position, breaks and collects a resource.
 * The filters and inventory counter are supplied by the calling objective.
 */
public final class GatherResourceTask implements NpcTask {
    private static final int SCAN_INTERVAL = 20;
    private static final int HORIZONTAL_RANGE = 8;
    private static final int VERTICAL_RANGE = 4;
    private static final long FAILED_BLOCK_TICKS = 1200;
    private static final long FAILED_ITEM_TICKS = 600;

    private enum Step { MOVE_TO_BLOCK, BREAK_BLOCK, COLLECT_ITEM }

    private final Predicate<BlockState> blockFilter;
    private final Predicate<ItemStack> itemFilter;
    private final ToIntFunction<NpcEntity> inventoryCount;
    private final int required;
    private final FailureMemory<BlockPos> failedBlocks = new FailureMemory<>();
    private final FailureMemory<UUID> failedItems = new FailureMemory<>();

    private NpcAction action;
    private Step step;
    private BlockPos blockTarget;
    private UUID itemTargetId;
    private long nextScan;
    private boolean needsMore = true;

    public GatherResourceTask(
            Predicate<BlockState> blockFilter,
            Predicate<ItemStack> itemFilter,
            ToIntFunction<NpcEntity> inventoryCount,
            int required
    ) {
        this.blockFilter = Objects.requireNonNull(blockFilter);
        this.itemFilter = Objects.requireNonNull(itemFilter);
        this.inventoryCount = Objects.requireNonNull(inventoryCount);
        if (required <= 0) throw new IllegalArgumentException("required must be positive");
        this.required = required;
    }

    @Override public String typeId() { return "wayside:gather_resource"; }
    @Override public String stageName() { return "COLLECTING"; }
    @Override public boolean wantsItem(ItemStack item) {
        return needsMore && itemFilter.test(item);
    }

    @Override
    public ActionResult tick(NpcEntity npc) {
        needsMore = inventoryCount.applyAsInt(npc) < required;
        if (!needsMore) {
            clearAction(npc);
            return ActionResult.success();
        }

        // If the world has changed during travel, discard the obsolete target.
        if (blockTarget != null && !isValidBlock(npc, blockTarget)) {
            clearAction(npc);
        }

        if (action != null) {
            ActionResult result = action.tick(npc);
            if (result.isRunning()) return result;

            Step finished = step;
            BlockPos attemptedBlock = blockTarget;
            UUID attemptedItem = itemTargetId;
            clearAction(npc);

            if (result.isFailed()) {
                if (finished == Step.COLLECT_ITEM && attemptedItem != null) {
                    failedItems.mark(attemptedItem, npc.level().getGameTime(), FAILED_ITEM_TICKS);
                } else if (attemptedBlock != null) {
                    failedBlocks.mark(attemptedBlock, npc.level().getGameTime(), FAILED_BLOCK_TICKS);
                }
                return result.failure() == ActionResult.Failure.NO_SPACE
                        ? result : ActionResult.running();
            }

            if (finished == Step.MOVE_TO_BLOCK
                    && attemptedBlock != null
                    && isValidBlock(npc, attemptedBlock)) {
                blockTarget = attemptedBlock;
                action = new BreakBlockAction(attemptedBlock, blockFilter);
                step = Step.BREAK_BLOCK;
            }
            return ActionResult.running();
        }

        long now = npc.level().getGameTime();
        if (!npc.onGround() || now < nextScan) return ActionResult.running();
        nextScan = now + SCAN_INTERVAL;

        // Dropped resources are picked up before more blocks are harvested.
        ItemEntity item = npc.level().getEntitiesOfClass(
                ItemEntity.class, npc.getBoundingBox().inflate(8),
                entity -> entity.isAlive()
                        && wantsItem(entity.getItem())
                        && npc.getInventory().canAddItem(entity.getItem())
                        && !failedItems.isCoolingDown(entity.getUUID(), now)
        ).stream().min(Comparator.comparingDouble(npc::distanceToSqr)).orElse(null);

        if (item != null) {
            itemTargetId = item.getUUID();
            action = new CollectItemAction(item);
            step = Step.COLLECT_ITEM;
            return ActionResult.running();
        }

        BlockPos candidate = BlockPos.findClosestMatch(
                npc.blockPosition(), HORIZONTAL_RANGE, VERTICAL_RANGE,
                pos -> npc.level().hasChunkAt(pos)
                        && blockFilter.test(npc.level().getBlockState(pos))
                        && !failedBlocks.isCoolingDown(pos, now)
        ).orElse(null);

        if (candidate != null) {
            blockTarget = candidate.immutable();
            beginBlockWork(npc);
        }
        return ActionResult.running();
    }

    private void beginBlockWork(NpcEntity npc) {
        if (NpcBlockInteraction.canWorkAt(npc, blockTarget, npc.position())) {
            action = new BreakBlockAction(blockTarget, blockFilter);
            step = Step.BREAK_BLOCK;
            return;
        }

        var stand = NpcBlockInteraction.findWorkPosition(npc, blockTarget);
        if (stand.isEmpty()) {
            failedBlocks.mark(blockTarget, npc.level().getGameTime(), FAILED_BLOCK_TICKS);
            blockTarget = null;
            return;
        }

        BlockPos resource = blockTarget;
        action = new MoveToAction(stand.get(), 1.0,
                actor -> NpcBlockInteraction.canWorkAt(actor, resource, actor.position()));
        step = Step.MOVE_TO_BLOCK;
    }

    private boolean isValidBlock(NpcEntity npc, BlockPos pos) {
        return npc.level().hasChunkAt(pos)
                && blockFilter.test(npc.level().getBlockState(pos));
    }

    private void clearAction(NpcEntity npc) {
        if (action != null) action.stop(npc);
        action = null;
        step = null;
        blockTarget = null;
        itemTargetId = null;
    }

    @Override public void pause(NpcEntity npc) { clearAction(npc); }
    @Override public void abandonWorkTarget(NpcEntity npc) {
        if (blockTarget != null) clearAction(npc);
    }
    @Override public BlockPos activeBlockTarget() { return blockTarget; }
    @Override public BlockPos activeMoveTarget() {
        return step == Step.MOVE_TO_BLOCK && action instanceof MoveToAction move
                ? move.targetPosition() : null;
    }
    @Override public boolean isBlockOnCooldown(BlockPos pos, long now) {
        return failedBlocks.isCoolingDown(pos, now);
    }
    @Override public void markBlockFailed(BlockPos pos, long now) {
        failedBlocks.mark(pos.immutable(), now, FAILED_BLOCK_TICKS);
    }
}
