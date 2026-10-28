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
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.phys.shapes.CollisionContext;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

// 查找可站立、可寻路且能实际操作目标方块的位置。
public final class NpcBlockInteraction {

    private static final double REACH = 2.5;
    private static final double SURFACE_INSET = 0.001;
    private static final int HORIZONTAL_WORK_RANGE = 3;
    private static final int MAX_PATH_ATTEMPTS = 32;

    private NpcBlockInteraction() {
    }

    // 纯条件检查：规划和实际放置都使用同一套支撑规则，不触发放置事件。
    public static boolean hasPlacementSupport(Level level, BlockPos pos, BlockState state) {
        return level.isInWorldBounds(pos) && level.hasChunkAt(pos)
                && state.canSurvive(level, pos)
                && (!(state.getBlock() instanceof FallingBlock)
                || !FallingBlock.isFree(level.getBlockState(pos.below())));
    }

    // 施工清障的基础规则；事件权限仍由实际破坏动作在执行时检查。
    public static boolean canDemolish(Level level, BlockPos pos, BlockState state) {
        return level.isInWorldBounds(pos) && level.hasChunkAt(pos)
                && !state.isAir() && state.getFluidState().isEmpty()
                && state.getDestroySpeed(level, pos) >= 0;
    }

    // 从假定站位检查支撑、操作距离和射线是否直接命中目标。
    public static boolean canWorkAt(NpcEntity npc, BlockPos target, Vec3 feet) {
        Level level = npc.level();
        if (!level.hasChunkAt(target) || !hasStandingSpace(npc, target, feet)) {
            return false;
        }

        Vec3 eye = eyePositionAt(npc, feet);
        if (!level.hasChunksAt(BlockPos.containing(eye), target)) return false;
        // 破坏按实际外形检查，台阶、玻璃板等也能选到有效操作表面。
        for (AABB box : level.getBlockState(target).getShape(level, target).toAabbs()) {
            AABB worldBox = box.move(target);
            Vec3 surface = new Vec3(
                    Mth.clamp(eye.x, worldBox.minX + SURFACE_INSET, worldBox.maxX - SURFACE_INSET),
                    Mth.clamp(eye.y, worldBox.minY + SURFACE_INSET, worldBox.maxY - SURFACE_INSET),
                    Mth.clamp(eye.z, worldBox.minZ + SURFACE_INSET, worldBox.maxZ - SURFACE_INSET));
            if (eye.distanceToSqr(surface) > REACH * REACH) continue;
            BlockHitResult hit = level.clip(new ClipContext(
                    eye, surface, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, npc));
            if (hit.getType() == HitResult.Type.BLOCK && target.equals(hit.getBlockPos())) return true;
        }
        return false;
    }

    // 放置目标是空气：视线应无遮挡，且新方块不能与假定站位的身体相交。
    public static boolean canPlaceAt(NpcEntity npc, BlockPos target, BlockState state, Vec3 feet) {
        Level level = npc.level();
        if (!level.hasChunkAt(target) || !level.getBlockState(target).isAir()
                || !hasStandingSpace(npc, target, feet)) return false;
        AABB body = npc.getDimensions(npc.getPose()).makeBoundingBox(feet);
        for (AABB box : state.getCollisionShape(level, target, CollisionContext.of(npc)).toAabbs()) {
            if (box.move(target).intersects(body)) return false;
        }
        Vec3 eye = eyePositionAt(npc, feet);
        if (!level.hasChunksAt(BlockPos.containing(eye), target)) return false;
        Vec3 surface = nearestSurfacePoint(target, eye);
        return eye.distanceToSqr(surface) <= REACH * REACH
                && level.clip(new ClipContext(eye, surface, ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE, npc)).getType() == HitResult.Type.MISS;
    }

    public static Optional<BlockPos> findPlacementPosition(
            NpcEntity npc, BlockPos target, BlockState state) {
        return findPosition(npc, target, feet -> canPlaceAt(npc, target, state, feet));
    }

    // 筛选附近站位，优先操作余量和距离，并限制寻路尝试次数。
    public static Optional<BlockPos> findWorkPosition(NpcEntity npc, BlockPos target) {
        return findPosition(npc, target, feet -> canWorkAt(npc, target, feet));
    }

    private static Optional<BlockPos> findPosition(NpcEntity npc, BlockPos target, Predicate<Vec3> usable) {
        List<BlockPos> candidates = new ArrayList<>();
        // 候选是 NPC 脚底的格子，不是待采集的方块。
        for (BlockPos candidate : BlockPos.betweenClosed(
                target.offset(-HORIZONTAL_WORK_RANGE, -4, -HORIZONTAL_WORK_RANGE),
                target.offset(HORIZONTAL_WORK_RANGE, 2, HORIZONTAL_WORK_RANGE)
        )) {
            if (usable.test(Vec3.atBottomCenterOf(candidate))) {
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
