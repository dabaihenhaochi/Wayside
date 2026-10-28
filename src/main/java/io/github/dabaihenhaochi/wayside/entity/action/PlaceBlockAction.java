package io.github.dabaihenhaochi.wayside.entity.action;

import io.github.dabaihenhaochi.wayside.entity.NpcEntity;
import io.github.dabaihenhaochi.wayside.entity.inventory.InventoryOps;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import io.github.dabaihenhaochi.wayside.building.BlockPlacementRules;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.neoforged.neoforge.common.util.BlockSnapshot;
import net.neoforged.neoforge.event.EventHooks;

import java.util.Objects;

/** 单格放置动作：材料规则与建筑材料计算器共享。移动由上层任务负责。 */
public final class PlaceBlockAction implements NpcAction {
    private static final double REACH = 2.5;
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
        if (!canReach(npc, level)) {
            return ActionResult.failed(ActionResult.Failure.UNREACHABLE);
        }
        if (!targetState.canSurvive(level, pos)
                || (targetState.getBlock() instanceof FallingBlock
                && FallingBlock.isFree(level.getBlockState(pos.below())))
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

    private boolean canReach(NpcEntity npc, ServerLevel level) {
        Vec3 eye = npc.getEyePosition();
        // 取靠近 NPC 的目标表面，距离按眼睛到表面计算。
        Vec3 aim = new Vec3(
                Mth.clamp(eye.x, pos.getX() + 0.001, pos.getX() + 0.999),
                Mth.clamp(eye.y, pos.getY() + 0.001, pos.getY() + 0.999),
                Mth.clamp(eye.z, pos.getZ() + 0.001, pos.getZ() + 0.999));
        if (eye.distanceToSqr(aim) > REACH * REACH
                || !level.hasChunksAt(BlockPos.containing(eye), pos)) {
            return false;
        }
        // 目标是空气：视线无遮挡时应为 MISS，而不是命中目标方块。
        return level.clip(new ClipContext(eye, aim, ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE, npc)).getType() == HitResult.Type.MISS;
    }
}

