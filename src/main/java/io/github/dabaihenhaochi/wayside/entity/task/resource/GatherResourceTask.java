package io.github.dabaihenhaochi.wayside.entity.task.resource;

import io.github.dabaihenhaochi.wayside.entity.task.FailureMemory;
import io.github.dabaihenhaochi.wayside.entity.task.NpcTask;

import io.github.dabaihenhaochi.wayside.entity.NpcEntity;
import io.github.dabaihenhaochi.wayside.entity.action.ActionResult;
import io.github.dabaihenhaochi.wayside.entity.action.BreakBlockAction;
import io.github.dabaihenhaochi.wayside.entity.action.CollectItemAction;
import io.github.dabaihenhaochi.wayside.entity.action.MoveToAction;
import io.github.dabaihenhaochi.wayside.entity.action.NpcAction;
import io.github.dabaihenhaochi.wayside.entity.ai.NpcBlockInteraction;
import io.github.dabaihenhaochi.wayside.entity.inventory.InventoryOps;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Comparator;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Predicate;

// 组合搜索、移动、破坏和拾取，直到调用方要求的库存数量达标。
public final class GatherResourceTask implements NpcTask {
    private static final int SCAN_INTERVAL = 20;
    private static final int HORIZONTAL_RANGE = 8;
    private static final int VERTICAL_RANGE = 4;
    private static final long FAILED_BLOCK_TICKS = 1200;
    private static final long FAILED_ITEM_TICKS = 600;

    private enum Step { MOVE_TO_BLOCK, BREAK_BLOCK, COLLECT_ITEM }

    private final Predicate<BlockState> blockFilter;
    private final Predicate<ItemStack> itemFilter;
    private final int required;
    private final FailureMemory<BlockPos> failedBlocks = new FailureMemory<>();
    private final FailureMemory<UUID> failedItems = new FailureMemory<>();

    private NpcAction action;
    private Step step;
    private BlockPos blockTarget;
    private UUID itemTargetId;
    private long nextScan;

    public GatherResourceTask(
            Predicate<BlockState> blockFilter,
            Predicate<ItemStack> itemFilter,
            int required
    ) {
        this.blockFilter = Objects.requireNonNull(blockFilter);
        this.itemFilter = Objects.requireNonNull(itemFilter);
        if (required <= 0) throw new IllegalArgumentException("required must be positive");
        this.required = required;
    }

    @Override public String typeId() { return "wayside:gather_resource"; }
    @Override public String stageName() { return "COLLECTING"; }
    // 推进当前动作，失败目标进入冷却；优先拾取掉落物，再寻找资源方块。
    @Override
    public ActionResult tick(NpcEntity npc) {
        // 拾取筛选与库存达标判断共用同一规则，调用方无需再提供计数回调。
        if (InventoryOps.count(npc.getInventory(), itemFilter) >= required) {
            clearAction(npc);
            return ActionResult.success();
        }

        // 行进途中目标失效时，丢弃旧动作和站位。
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

            // 背包满时上报失败，其余失败先记冷却，再继续寻找目标。
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

        // 先收集可装入背包且未冷却的附近掉落物。
        ItemEntity item = npc.level().getEntitiesOfClass(
                ItemEntity.class, npc.getBoundingBox().inflate(8),
                entity -> entity.isAlive()
                        && itemFilter.test(entity.getItem())
                        && npc.getInventory().canAddItem(entity.getItem())
                        && !failedItems.isCoolingDown(entity.getUUID(), now)
        ).stream().min(Comparator.comparingDouble(npc::distanceToSqr)).orElse(null);

        if (item != null) {
            itemTargetId = item.getUUID();
            action = new CollectItemAction(item, itemFilter);
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

    // 能直接操作则开始破坏，否则寻找站位；无路可达时记入冷却。
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

    // 停止动作并清空临时目标，保留失败冷却记录。
    private void clearAction(NpcEntity npc) {
        if (action != null) action.stop(npc);
        action = null;
        step = null;
        blockTarget = null;
        itemTargetId = null;
    }

    @Override public void pause(NpcEntity npc) { clearAction(npc); }
    @Override public BlockPos activeBlockTarget() { return blockTarget; }
    @Override public BlockPos activeMoveTarget() {
        return step == Step.MOVE_TO_BLOCK && action instanceof MoveToAction move
                ? move.targetPosition() : null;
    }
    public boolean isBlockOnCooldown(BlockPos pos, long now) {
        return failedBlocks.isCoolingDown(pos, now);
    }
}
