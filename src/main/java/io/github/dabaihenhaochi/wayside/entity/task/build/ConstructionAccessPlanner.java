package io.github.dabaihenhaochi.wayside.entity.task.build;

import io.github.dabaihenhaochi.wayside.building.ConstructionOperation;
import io.github.dabaihenhaochi.wayside.entity.NpcEntity;
import io.github.dabaihenhaochi.wayside.entity.ai.NpcBlockInteraction;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.function.Predicate;

/** 仅对已确认寻路失败的严格操作，检查有限垫高能否打开该目标。 */
final class ConstructionAccessPlanner {
    private ConstructionAccessPlanner() {}

    static BlockPos findPillarBase(NpcEntity npc, List<ConstructionOperation> operations,
                                   int maxRise, Predicate<BlockPos> allowed) {
        if (!npc.onGround() || maxRise < 1) return null;
        BlockPos base = npc.blockPosition();
        if (!allowed.test(base) || !npc.level().hasChunkAt(base.below())
                || !npc.level().getBlockState(base).isAir() || !npc.level().getFluidState(base.below()).isEmpty()
                || !npc.level().getBlockState(base.below()).isFaceSturdy(npc.level(), base.below(), Direction.UP)) return null;
        for (int rise = 1; rise <= maxRise; rise++) {
            // 到有效高度之前需要填入的每一格都必须在保护范围之外。
            BlockPos fill = base.above(rise - 1);
            if (!npc.level().isInWorldBounds(fill) || !npc.level().getWorldBorder().isWithinBounds(fill)
                    || !allowed.test(fill)) return null;
            var column = npc.getDimensions(npc.getPose()).makeBoundingBox(Vec3.atBottomCenterOf(base))
                    .expandTowards(0, rise + 0.02, 0);
            if (!npc.level().hasChunksAt(base, BlockPos.containing(column.maxX, column.maxY, column.maxZ))
                    || !npc.level().noCollision(npc, column) || npc.level().containsAnyLiquid(column)) return null;
            Vec3 feet = Vec3.atBottomCenterOf(base.above(rise));
            for (var operation : operations) {
                if (operation.pos().getY() <= base.getY() || !npc.level().isLoaded(operation.pos())) continue;
                var current = npc.level().getBlockState(operation.pos());
                if (current.equals(operation.targetState())) continue;
                boolean breaking = operation.action() != ConstructionOperation.Action.PLACE;
                boolean useful = breaking
                        ? NpcBlockInteraction.canDemolish(npc.level(), operation.pos(), current)
                            && NpcBlockInteraction.canReachBlockFrom(npc, operation.pos(), feet)
                        : NpcBlockInteraction.hasPlacementSupport(npc.level(), operation.pos(), operation.targetState())
                            && NpcBlockInteraction.canReachPlacementFrom(npc, operation.pos(), operation.targetState(), feet);
                if (useful) return base.immutable();
            }
        }
        return null;
    }
}
