package io.github.dabaihenhaochi.wayside.entity.action;

import io.github.dabaihenhaochi.wayside.entity.NpcEntity;
import io.github.dabaihenhaochi.wayside.entity.inventory.InventoryOps;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import java.util.Objects;
import java.util.function.Predicate;

/** 原地跳起、用一块泥土垫高、落稳。放置事务与扣料交给已有 PlaceBlockAction。 */
public final class PillarUpAction implements NpcAction {
    private final BlockPos expectedBase;
    private final Predicate<BlockPos> placementAllowed;
    private final int reservedDirt;
    private BlockPos base;
    private MoveToAction centering;
    private PlaceBlockAction placement;
    private long startedAt = -1;
    private boolean jumped;
    private boolean placed;

    public PillarUpAction() { expectedBase = null; placementAllowed = pos -> true; reservedDirt = 0; }
    public PillarUpAction(BlockPos base) { this(base, pos -> true); }
    public PillarUpAction(BlockPos base, Predicate<BlockPos> placementAllowed) { this(base, placementAllowed, 0); }
    public PillarUpAction(BlockPos base, Predicate<BlockPos> placementAllowed, int reservedDirt) {
        if (reservedDirt < 0) throw new IllegalArgumentException("Reserved dirt cannot be negative");
        expectedBase = Objects.requireNonNull(base).immutable();
        this.placementAllowed = Objects.requireNonNull(placementAllowed);
        this.reservedDirt = reservedDirt;
    }

    @Override public ActionResult tick(NpcEntity npc) {
        if (!(npc.level() instanceof ServerLevel level)) return ActionResult.failed(ActionResult.Failure.INVALID_TARGET);
        if (startedAt < 0) {
            if (!npc.onGround()) return ActionResult.failed(ActionResult.Failure.UNREACHABLE);
            base = npc.blockPosition().immutable();
            if (expectedBase != null && !expectedBase.equals(base)) return ActionResult.failed(ActionResult.Failure.WORLD_CHANGED);
            startedAt = level.getGameTime();
            centering = new MoveToAction(base, 0.5, actor -> actor.onGround()
                    && actor.position().distanceToSqr(Vec3.atBottomCenterOf(base)) < 0.01);
            placement = PlaceBlockAction.underfoot(base, Blocks.DIRT.defaultBlockState(), reservedDirt, placementAllowed);
        }
        if (level.getGameTime() - startedAt > 80) return ActionResult.failed(ActionResult.Failure.TIMED_OUT);
        if (!level.isInWorldBounds(base.above(3)) || !level.hasChunksAt(base.below(), base.above(3))) {
            return ActionResult.failed(ActionResult.Failure.INVALID_TARGET);
        }
        if (placed) {
            if (!level.getBlockState(base).is(Blocks.DIRT)) return ActionResult.failed(ActionResult.Failure.WORLD_CHANGED);
            if (npc.onGround() && npc.blockPosition().equals(base.above())
                    && Math.abs(npc.getY() - base.getY() - 1) < 0.05) return ActionResult.success();
            return ActionResult.running();
        }
        // 规划后新增的建筑预留也必须生效；检查到实际落块前，不能扣料后再撤回。
        if (!placementAllowed.test(base)) return ActionResult.failed(ActionResult.Failure.WORLD_CHANGED);
        if (!jumped) {
            if (InventoryOps.count(npc.getInventory(), Items.DIRT) <= reservedDirt) {
                return ActionResult.failed(ActionResult.Failure.MISSING_INGREDIENT);
            }
            if (!level.getBlockState(base).isAir() || !level.getFluidState(base.below()).isEmpty()
                    || !level.getBlockState(base.below()).isFaceSturdy(level, base.below(), Direction.UP)
                    || !level.noCollision(npc, npc.getBoundingBox().expandTowards(0, 1.02, 0))
                    || level.containsAnyLiquid(npc.getBoundingBox().expandTowards(0, 1.02, 0))) {
                return ActionResult.failed(ActionResult.Failure.NO_SPACE);
            }
            ActionResult centered = centering.tick(npc);
            if (!centered.isSuccess()) return centered;
            centering.stop(npc);
            npc.getMoveControl().setWantedPosition(npc.getX(), npc.getY(), npc.getZ(), 0);
            npc.getJumpControl().jump();
            jumped = true;
            return ActionResult.running();
        }
        if (npc.getY() >= base.getY() + 1.01) {
            ActionResult result = placement.tick(npc);
            if (result.isSuccess()) { placed = true; return ActionResult.running(); }
            return result;
        }
        return ActionResult.running();
    }

    public BlockPos targetPosition() { return base; }
    public boolean hasPlacedBlock() { return placed; }
    @Override public void stop(NpcEntity npc) {
        if (centering != null) centering.stop(npc);
        if (placement != null) placement.stop(npc);
        npc.getNavigation().stop();
    }
}
