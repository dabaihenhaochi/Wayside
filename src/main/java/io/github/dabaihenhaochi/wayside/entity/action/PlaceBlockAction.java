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
import java.util.function.Predicate;

/** 单格放置动作：材料规则与建筑材料计算器共享。移动由上层任务负责。 */
public final class PlaceBlockAction implements NpcAction {
    private enum Mode { NORMAL, UNDERFOOT, CONSTRUCTION_FALLBACK }
    private final BlockPos pos;
    private final BlockState targetState;
    private final Mode mode;
    private final int reservedItems;
    private final Predicate<BlockPos> placementAllowed;

    public PlaceBlockAction(BlockPos pos, BlockState targetState) {
        this(pos, targetState, Mode.NORMAL, 0, target -> true);
    }

    private PlaceBlockAction(BlockPos pos, BlockState targetState, Mode mode, int reservedItems,
                             Predicate<BlockPos> placementAllowed) {
        if (reservedItems < 0) throw new IllegalArgumentException("Reserved material cannot be negative");
        this.pos = Objects.requireNonNull(pos).immutable();
        this.targetState = Objects.requireNonNull(targetState);
        this.mode = mode;
        this.reservedItems = reservedItems;
        this.placementAllowed = Objects.requireNonNull(placementAllowed);
    }

    static PlaceBlockAction underfoot(BlockPos pos, BlockState state, int reservedItems,
                                      Predicate<BlockPos> placementAllowed) {
        return new PlaceBlockAction(pos, state, Mode.UNDERFOOT, reservedItems, placementAllowed);
    }

    /** 仅供建筑任务在正常站位/移动受阻后使用；不绕过材料、支撑、碰撞或放置事件。 */
    public static PlaceBlockAction constructionFallback(BlockPos pos, BlockState state) {
        return new PlaceBlockAction(pos, state, Mode.CONSTRUCTION_FALLBACK, 0, target -> true);
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
        if (!placementAllowed.test(pos)) return ActionResult.failed(ActionResult.Failure.WORLD_CHANGED);
        boolean reachable = switch (mode) {
            case NORMAL -> NpcBlockInteraction.canPlaceAt(npc, pos, targetState, npc.position());
            case UNDERFOOT -> NpcBlockInteraction.canPlaceUnderfoot(npc, pos, targetState);
            case CONSTRUCTION_FALLBACK -> true;
        };
        if (!reachable) {
            return ActionResult.failed(ActionResult.Failure.UNREACHABLE);
        }
        if (!NpcBlockInteraction.hasPlacementSupport(level, pos, targetState)
                || !level.isUnobstructed(targetState, pos, CollisionContext.empty())) {
            return ActionResult.failed(ActionResult.Failure.NO_SPACE);
        }
        // 隔空只放宽距离和视线，不能把方块放进施工者身体。
        if (mode == Mode.CONSTRUCTION_FALLBACK && targetState.getCollisionShape(level, pos).toAabbs().stream()
                .anyMatch(box -> box.move(pos).intersects(npc.getBoundingBox()))) {
            return ActionResult.failed(ActionResult.Failure.NO_SPACE);
        }

        Item material = cost.getItem();
        if (!hasMaterials(npc, cost)) {
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
            // 事件回调可能改变建筑预留或背包；在最终提交点再次检查。
            if (!placementAllowed.test(pos)) return ActionResult.failed(ActionResult.Failure.WORLD_CHANGED);
            if (!hasMaterials(npc, cost) || !InventoryOps.consumeExact(npc.getInventory(), material, cost.getCount())) {
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

    private boolean hasMaterials(NpcEntity npc, ItemStack cost) {
        return InventoryOps.count(npc.getInventory(), cost.getItem()) >= (long) cost.getCount() + reservedItems;
    }

}
