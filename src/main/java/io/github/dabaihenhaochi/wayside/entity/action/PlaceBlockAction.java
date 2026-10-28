package io.github.dabaihenhaochi.wayside.entity.action;

import io.github.dabaihenhaochi.wayside.entity.NpcEntity;
import io.github.dabaihenhaochi.wayside.entity.inventory.InventoryOps;
import io.github.dabaihenhaochi.wayside.entity.ai.NpcBlockInteraction;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import io.github.dabaihenhaochi.wayside.building.BlockPlacementRules;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.neoforged.neoforge.common.util.BlockSnapshot;
import net.neoforged.neoforge.event.EventHooks;

import java.util.Objects;

/** 单格放置动作：材料规则与建筑材料计算器共享。移动由上层任务负责。 */
public final class PlaceBlockAction implements NpcAction {
    private final BlockPos pos;
    private final BlockState targetState;

    public PlaceBlockAction(BlockPos pos, BlockState targetState) {
        this.pos = Objects.requireNonNull(pos).immutable();
        this.targetState = Objects.requireNonNull(targetState);
    }

    @Override
    public ActionResult tick(NpcEntity npc) {
        if (!(npc.level() instanceof ServerLevel level)) {
            return ActionResult.failed(ActionResult.Failure.INVALID_TARGET);
        }
        if (!level.isInWorldBounds(pos) || !level.isLoaded(pos)
                || !level.getWorldBorder().isWithinBounds(pos)) {
            return ActionResult.failed(ActionResult.Failure.INVALID_TARGET);
        }
        ItemStack cost = BlockPlacementRules.costFor(targetState).orElse(null);
        if (cost == null || !targetState.getBlock().isEnabled(level.enabledFeatures())) {
            return ActionResult.failed(ActionResult.Failure.INVALID_TARGET);
        }

        BlockState originalState = level.getBlockState(pos);
        if (originalState.equals(targetState)) {
            return ActionResult.success();
        }
        if (!originalState.isAir()) {
            return ActionResult.failed(ActionResult.Failure.WORLD_CHANGED);
        }

        // 不嵌入其他操作正在进行的方块快照事务。
        if (level.captureBlockSnapshots || !EventHooks.canEntityGrief(level, npc)) {
            return ActionResult.failed(ActionResult.Failure.FORBIDDEN);
        }
        if (!NpcBlockInteraction.canPlaceAt(npc, pos, targetState, npc.position())) {
            return ActionResult.failed(ActionResult.Failure.UNREACHABLE);
        }
        if (!NpcBlockInteraction.hasPlacementSupport(level, pos, targetState)
                || !level.isUnobstructed(targetState, pos, CollisionContext.empty())) {
            return ActionResult.failed(ActionResult.Failure.NO_SPACE);
        }

        Item material = cost.getItem();
        if (InventoryOps.count(npc.getInventory(), material) < cost.getCount()) {
            return ActionResult.failed(ActionResult.Failure.MISSING_INGREDIENT);
        }
        if (!level.getBlockState(pos).equals(originalState)) {
            return ActionResult.failed(ActionResult.Failure.WORLD_CHANGED);
        }

        // 先临时写入，不发送客户端或邻居更新。此时尚未扣料。
        if (!level.setBlock(pos, targetState, Block.UPDATE_KNOWN_SHAPE)) {
            return ActionResult.failed(ActionResult.Failure.WORLD_CHANGED);
        }

        boolean committed = false;
        try {
            // 本版本的非玩家放置事件从快照读取拟放置方块，
            // 因此在临时写入后创建事件快照。
            BlockSnapshot placed = BlockSnapshot.create(level.dimension(), level, pos);
            if (EventHooks.onBlockPlace(npc, placed, Direction.UP)) {
                return ActionResult.failed(ActionResult.Failure.FORBIDDEN);
            }
            if (!level.getBlockState(pos).equals(targetState)) {
                return ActionResult.failed(ActionResult.Failure.WORLD_CHANGED);
            }
            if (!InventoryOps.consumeExact(npc.getInventory(), material, cost.getCount())) {
                return ActionResult.failed(ActionResult.Failure.MISSING_INGREDIENT);
            }
            committed = true;
        } finally {
            // 失败时撤回本动作仍占有的临时方块，不覆盖事件处理器的新修改。
            if (!committed && level.getBlockState(pos).equals(targetState)) {
                level.setBlock(pos, originalState, Block.UPDATE_KNOWN_SHAPE);
            }
        }

        // 世界写入和扣料已成功，现在同步画面并通知邻居。
        level.markAndNotifyBlock(pos, level.getChunkAt(pos), originalState,
                targetState, Block.UPDATE_ALL, 512);
        npc.getLookControl().setLookAt(pos.getX() + 0.5,
                pos.getY() + 0.5, pos.getZ() + 0.5);
        npc.swing(InteractionHand.MAIN_HAND);
        level.gameEvent(GameEvent.BLOCK_PLACE, pos,
                GameEvent.Context.of(npc, targetState));
        return ActionResult.success();
    }

}
