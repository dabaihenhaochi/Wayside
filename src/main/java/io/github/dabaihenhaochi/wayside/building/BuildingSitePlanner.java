package io.github.dabaihenhaochi.wayside.building;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;

/** 只读的选址与土方规划。输入建筑尺寸和限制，输出可保存、可估算材料的场地计划。 */
public final class BuildingSitePlanner {
    private BuildingSitePlanner() {}

    public static List<BlockPos> candidates(BlockPos start, int radius, int heightRange) {
        var result = new ArrayList<BlockPos>();
        for (int x = -radius; x <= radius; x++) for (int z = -radius; z <= radius; z++) {
            if (Math.max(Math.abs(x), Math.abs(z)) < 3) continue;
            for (int y = -heightRange; y <= heightRange; y++) result.add(start.offset(x, y, z));
        }
        result.sort(Comparator.comparingDouble((BlockPos pos) -> pos.distSqr(start))
                .thenComparingInt(pos -> Math.abs(pos.getY() - start.getY())));
        return List.copyOf(result);
    }

    public static Optional<BuildingSitePlan> plan(ServerLevel level, BuildingBlueprint blueprint,
                                                 BuildingInstance building, int maxFillDepth, int maxCutDepth) {
        if (maxFillDepth < 0 || maxFillDepth > 8 || maxCutDepth < 0) throw new IllegalArgumentException("Invalid earthwork limits");
        var bounds = building.getBoundingBox(blueprint);
        BlockPos min = new BlockPos(bounds.minX(), bounds.minY() - maxFillDepth - 1, bounds.minZ());
        BlockPos max = new BlockPos(bounds.maxX(), Math.max(bounds.maxY(), bounds.minY() + maxCutDepth), bounds.maxZ());
        if (!level.isInWorldBounds(min) || !level.isInWorldBounds(max) || !level.hasChunksAt(min, max)
                || !level.getWorldBorder().isWithinBounds(min) || !level.getWorldBorder().isWithinBounds(max)) return Optional.empty();
        var fill = new LinkedHashSet<BlockPos>();
        for (int x = bounds.minX(); x <= bounds.maxX(); x++) for (int z = bounds.minZ(); z <= bounds.maxZ(); z++) {
            BlockPos top = new BlockPos(x, bounds.minY() - 1, z);
            BlockPos anchor = top;
            while (!level.getBlockState(anchor).isFaceSturdy(level, anchor, Direction.UP)) {
                if (top.getY() - anchor.getY() >= maxFillDepth
                        || !BuildingSitePlan.safeToChange(level, anchor, level.getBlockState(anchor))) return Optional.empty();
                fill.add(anchor);
                anchor = anchor.below();
            }
            if (!level.getFluidState(anchor).isEmpty() || level.getBlockState(anchor).hasBlockEntity()) return Optional.empty();
            // 限制连续地层的挖深；不把小屋建在需要大规模挖山的地方。
            int cut = 0;
            for (BlockPos pos = top.above(); level.getBlockState(pos).isFaceSturdy(level, pos, Direction.UP); pos = pos.above()) {
                if (++cut > maxCutDepth) return Optional.empty();
            }
        }
        BuildingSitePlan site = new BuildingSitePlan(level.dimension().location(), bounds, fill);
        if (site.remaining(level).unavailablePositions() != 0) return Optional.empty();
        return Optional.of(site);
    }
}
