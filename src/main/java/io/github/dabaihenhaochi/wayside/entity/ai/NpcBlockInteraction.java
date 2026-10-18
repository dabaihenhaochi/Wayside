package io.github.dabaihenhaochi.wayside.entity.ai;

import io.github.dabaihenhaochi.wayside.entity.NpcEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

public final class NpcBlockInteraction {

    private static final double REACH = 2.5;
    private static final double SURFACE_INSET = 0.001;
    private static final int HORIZONTAL_WORK_RANGE = 3;
    private static final int MAX_PATH_ATTEMPTS = 32;

    private NpcBlockInteraction() {
    }

    public static boolean canWorkAt(NpcEntity npc, BlockPos target, Vec3 feet) {
        Level level = npc.level();
        if (!level.hasChunkAt(target) || !hasStandingSpace(npc, target, feet)) {
            return false;
        }

        Vec3 eye = eyePositionAt(npc, feet);
        Vec3 surface = nearestSurfacePoint(target, eye);
        if (eye.distanceToSqr(surface) > REACH * REACH) {
            return false;
        }

        BlockHitResult hit = level.clip(new ClipContext(
                eye, surface, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, npc
        ));
        return hit.getType() == HitResult.Type.BLOCK && target.equals(hit.getBlockPos());
    }

    public static Optional<BlockPos> findWorkPosition(NpcEntity npc, BlockPos target) {
        List<BlockPos> candidates = new ArrayList<>();
        // 候选是 NPC 脚底的格子，不是待采集的方块。
        for (BlockPos candidate : BlockPos.betweenClosed(
                target.offset(-HORIZONTAL_WORK_RANGE, -4, -HORIZONTAL_WORK_RANGE),
                target.offset(HORIZONTAL_WORK_RANGE, 2, HORIZONTAL_WORK_RANGE)
        )) {
            if (canWorkAt(npc, target, Vec3.atBottomCenterOf(candidate))) {
                candidates.add(candidate.immutable());
            }
        }

        // 优先留出操作余量，避免寻路在格子边缘停下时刚好够不到。
        candidates.sort(Comparator
                .comparingInt((BlockPos pos) -> hasComfortableReach(npc, target, pos) ? 0 : 1)
                .thenComparingDouble(pos -> npc.position().distanceToSqr(Vec3.atBottomCenterOf(pos))));

        int attempts = 0;
        for (BlockPos candidate : candidates) {
            if (attempts++ >= MAX_PATH_ATTEMPTS) {
                break;
            }
            Path path = npc.getNavigation().createPath(candidate, 0);
            if (path != null && path.canReach()) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    private static boolean hasStandingSpace(NpcEntity npc, BlockPos target, Vec3 feet) {
        Level level = npc.level();
        AABB body = npc.getDimensions(npc.getPose()).makeBoundingBox(feet);
        BlockPos support = BlockPos.containing(feet.x, feet.y - 0.01, feet.z);
        if (!level.hasChunksAt(BlockPos.containing(body.minX, feet.y - 1.0, body.minZ),
                BlockPos.containing(body.maxX, body.maxY, body.maxZ))) {
            return false;
        }

        // 当前版本使用完整顶面的落脚处；先移到旁边，再采集脚下原木。
        return !support.equals(target)
                && Math.abs(feet.y - (support.getY() + 1.0)) < 0.05
                && level.getBlockState(support).isFaceSturdy(level, support, Direction.UP)
                && level.getFluidState(support).isEmpty()
                && !level.containsAnyLiquid(body)
                && level.noCollision(npc, body);
    }

    private static Vec3 eyePositionAt(NpcEntity npc, Vec3 feet) {
        return feet.add(0.0, npc.getEyeY() - npc.getY(), 0.0);
    }

    private static Vec3 nearestSurfacePoint(BlockPos target, Vec3 eye) {
        // 终点略微伸入方块，射线才能可靠命中朝向 NPC 的表面。
        return new Vec3(
                Mth.clamp(eye.x, target.getX() + SURFACE_INSET, target.getX() + 1.0 - SURFACE_INSET),
                Mth.clamp(eye.y, target.getY() + SURFACE_INSET, target.getY() + 1.0 - SURFACE_INSET),
                Mth.clamp(eye.z, target.getZ() + SURFACE_INSET, target.getZ() + 1.0 - SURFACE_INSET)
        );
    }

    private static boolean hasComfortableReach(NpcEntity npc, BlockPos target, BlockPos feet) {
        Vec3 eye = eyePositionAt(npc, Vec3.atBottomCenterOf(feet));
        return eye.distanceToSqr(nearestSurfacePoint(target, eye)) <= 4.0;
    }
}
