package io.github.dabaihenhaochi.wayside.entity.task;

import io.github.dabaihenhaochi.wayside.entity.NpcEntity;
import io.github.dabaihenhaochi.wayside.entity.action.ActionResult;
import io.github.dabaihenhaochi.wayside.entity.action.BreakBlockAction;
import io.github.dabaihenhaochi.wayside.entity.action.CollectItemAction;
import io.github.dabaihenhaochi.wayside.entity.action.CraftSingleIngredientAction;
import io.github.dabaihenhaochi.wayside.entity.action.MoveToAction;
import io.github.dabaihenhaochi.wayside.entity.action.NpcAction;
import io.github.dabaihenhaochi.wayside.entity.ai.NpcBlockInteraction;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Comparator;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Migration of the v0.0.6 wood loop: collect 16 logs, then craft planks.
 * Only this task decides which resource to collect and what happens on failure.
 */
public final class GatherAndCraftPlanksTask implements NpcTask {
    public static final String ID = "wayside:gather_and_craft_planks";

    private static final int TARGET_LOGS = 16;
    private static final int SCAN_INTERVAL = 20;
    private static final int HORIZONTAL_RANGE = 8;
    private static final int VERTICAL_RANGE = 4;
    private static final long FAILED_BLOCK_TICKS = 1200;
    private static final long FAILED_ITEM_TICKS = 600;

    private static final Predicate<BlockState> LOG_BLOCK = state -> state.is(BlockTags.LOGS);

    private enum Stage { COLLECTING, CRAFTING, COMPLETED }
    private enum Step { APPROACH_BLOCK, BREAK_BLOCK, COLLECT_ITEM, CRAFT }

    private Stage stage = Stage.COLLECTING;
    private Step step;
    private NpcAction action;
    private BlockPos blockTarget;
    private UUID itemTargetId;
    private long nextScanTime;

    private final FailureMemory<BlockPos> failedBlocks = new FailureMemory<>();
    private final FailureMemory<UUID> failedItems = new FailureMemory<>();

    @Override
    public String typeId() { return ID; }

    @Override
    public String stageName() { return stage.name(); }

    @Override
    public boolean wantsItem(ItemStack item) {
        return stage == Stage.COLLECTING
                && item.is(ItemTags.LOGS)
                && ownerNeedsMoreLogs;
    }

    // Updated immediately before each task tick; item pickup happens on the NPC tick.
    private boolean ownerNeedsMoreLogs = true;

    @Override
    public ActionResult tick(NpcEntity npc) {
        ownerNeedsMoreLogs = npc.getLogCount() < TARGET_LOGS;

        if (stage == Stage.COMPLETED) {
            return ActionResult.success();
        }
        if (stage == Stage.COLLECTING && !ownerNeedsMoreLogs) {
            clearAction(npc);
            stage = Stage.CRAFTING;
        }

        if (stage == Stage.CRAFTING && action == null) {
            action = new CraftSingleIngredientAction(
                    stack -> stack.is(ItemTags.LOGS),
                    stack -> stack.is(ItemTags.PLANKS));
            step = Step.CRAFT;
        }

        if (stage == Stage.COLLECTING && blockTarget != null
                && !validLog(npc, blockTarget)) {
            // World changed while walking or working. Do not keep stale targets.
            clearAction(npc);
        }

        if (action != null) {
            ActionResult result = action.tick(npc);
            if (result.isRunning()) {
                return result;
            }
            Step completedStep = step;
            BlockPos attemptedBlock = blockTarget;
            UUID attemptedItem = itemTargetId;
            clearAction(npc);

            if (result.isFailed()) {
                if (completedStep == Step.COLLECT_ITEM && attemptedItem != null) {
                    failedItems.mark(attemptedItem,
                            npc.level().getGameTime(), FAILED_ITEM_TICKS);
                } else if (attemptedBlock != null) {
                    failedBlocks.mark(attemptedBlock,
                            npc.level().getGameTime(), FAILED_BLOCK_TICKS);
                }
                return completedStep == Step.CRAFT || result.failure() == ActionResult.Failure.NO_SPACE
                        ? result : ActionResult.running();
            }

            if (completedStep == Step.APPROACH_BLOCK && attemptedBlock != null
                    && validLog(npc, attemptedBlock)) {
                blockTarget = attemptedBlock;
                action = new BreakBlockAction(attemptedBlock, LOG_BLOCK);
                step = Step.BREAK_BLOCK;
                return ActionResult.running();
            }
            if (completedStep == Step.CRAFT) {
                stage = Stage.COMPLETED;
                return ActionResult.success();
            }
            return ActionResult.running();
        }

        if (stage != Stage.COLLECTING) {
            return ActionResult.running();
        }

        long now = npc.level().getGameTime();
        if (now < nextScanTime) {
            return ActionResult.running();
        }
        nextScanTime = now + SCAN_INTERVAL;

        ItemEntity item = npc.level().getEntitiesOfClass(
                ItemEntity.class,
                npc.getBoundingBox().inflate(8),
                candidate -> candidate.isAlive()
                        && !candidate.hasPickUpDelay()
                        && wantsItem(candidate.getItem())
                        && npc.getInventory().canAddItem(candidate.getItem())
                        && !failedItems.isCoolingDown(candidate.getUUID(), now)
        ).stream().min(Comparator.comparingDouble(npc::distanceToSqr)).orElse(null);

        if (item != null) {
            itemTargetId = item.getUUID();
            action = new CollectItemAction(item);
            step = Step.COLLECT_ITEM;
            return ActionResult.running();
        }

        BlockPos found = BlockPos.findClosestMatch(
                npc.blockPosition(), HORIZONTAL_RANGE, VERTICAL_RANGE,
                pos -> npc.level().hasChunkAt(pos)
                        && LOG_BLOCK.test(npc.level().getBlockState(pos))
                        && !failedBlocks.isCoolingDown(pos, now)
        ).orElse(null);

        if (found != null) {
            blockTarget = found.immutable();
            beginBlockWork(npc);
        }
        return ActionResult.running();
    }

    private void beginBlockWork(NpcEntity npc) {
        if (blockTarget == null) {
            return;
        }
        if (NpcBlockInteraction.canWorkAt(npc, blockTarget, npc.position())) {
            action = new BreakBlockAction(blockTarget, LOG_BLOCK);
            step = Step.BREAK_BLOCK;
            return;
        }

        var workPos = NpcBlockInteraction.findWorkPosition(npc, blockTarget);
        if (workPos.isEmpty()) {
            failedBlocks.mark(blockTarget,
                    npc.level().getGameTime(), FAILED_BLOCK_TICKS);
            blockTarget = null;
            return;
        }

        BlockPos target = blockTarget;
        action = new MoveToAction(workPos.get(), 1.0,
                mob -> NpcBlockInteraction.canWorkAt(mob, target, mob.position()));
        step = Step.APPROACH_BLOCK;
    }

    private boolean validLog(NpcEntity npc, BlockPos pos) {
        return npc.level().hasChunkAt(pos)
                && LOG_BLOCK.test(npc.level().getBlockState(pos));
    }

    private void clearAction(NpcEntity npc) {
        if (action != null) {
            action.stop(npc);
        }
        action = null;
        step = null;
        blockTarget = null;
        itemTargetId = null;
    }

    @Override
    public void pause(NpcEntity npc) {
        clearAction(npc);
    }

    @Override
    public void abandonWorkTarget(NpcEntity npc) {
        if (blockTarget != null) {
            clearAction(npc);
        }
    }

    @Override
    public BlockPos activeBlockTarget() {
        return blockTarget;
    }

    @Override
    public BlockPos activeMoveTarget() {
        return step == Step.APPROACH_BLOCK && action instanceof MoveToAction move
                ? move.targetPosition() : null;
    }

    @Override
    public boolean isBlockOnCooldown(BlockPos pos, long now) {
        return failedBlocks.isCoolingDown(pos, now);
    }

    @Override
    public void markBlockFailed(BlockPos pos, long now) {
        failedBlocks.mark(pos.immutable(), now, FAILED_BLOCK_TICKS);
    }

    @Override
    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("Stage", stage.name());
        return tag;
    }

    @Override
    public void load(CompoundTag tag) {
        try {
            stage = Stage.valueOf(tag.getString("Stage"));
        } catch (IllegalArgumentException exception) {
            stage = Stage.COLLECTING;
        }
        ownerNeedsMoreLogs = true;
    }
}
