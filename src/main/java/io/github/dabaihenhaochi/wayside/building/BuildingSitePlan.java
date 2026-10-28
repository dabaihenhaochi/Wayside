package io.github.dabaihenhaochi.wayside.building;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/** 已选场地的数据：建筑净空和需要补泥土的坐标。不会移动 NPC 或修改世界。 */
public record BuildingSitePlan(ResourceLocation dimension, BoundingBox clearance, Set<BlockPos> fill) {
    public BuildingSitePlan {
        Objects.requireNonNull(dimension);
        Objects.requireNonNull(clearance);
        clearance = new BoundingBox(clearance.minX(), clearance.minY(), clearance.minZ(),
                clearance.maxX(), clearance.maxY(), clearance.maxZ());
        fill = Set.copyOf(fill);
        if ((long) clearance.getXSpan() * clearance.getYSpan() * clearance.getZSpan() > 32768
                || fill.size() > 4096) throw new IllegalArgumentException("Site is too large");
        for (BlockPos pos : fill) {
            if (pos.getX() < clearance.minX() || pos.getX() > clearance.maxX()
                    || pos.getZ() < clearance.minZ() || pos.getZ() > clearance.maxZ()
                    || pos.getY() >= clearance.minY() || pos.getY() < clearance.minY() - 8) {
                throw new IllegalArgumentException("Fill outside foundation");
            }
        }
    }

    public BoundingBox reservedBounds() {
        int bottom = fill.stream().mapToInt(BlockPos::getY).min().orElse(clearance.minY() - 1);
        return new BoundingBox(clearance.minX(), bottom, clearance.minZ(),
                clearance.maxX(), clearance.maxY(), clearance.maxZ());
    }

    /** 每次按现场重新计算剩余工作，读档后不会重复扣料或重新选址。 */
    public ConstructionPlanner.Plan remaining(ServerLevel level) {
        var tasks = new ArrayList<ConstructionOperation>();
        if (!dimension.equals(level.dimension().location()) || !available(level)) {
            return new ConstructionPlanner.Plan(tasks, 1, 0);
        }
        // 每根填土柱必须连续接到可靠地面；现场支撑消失时停止，而不是悬空补土。
        for (int x = clearance.minX(); x <= clearance.maxX(); x++) {
            for (int z = clearance.minZ(); z <= clearance.maxZ(); z++) {
                BlockPos anchor = new BlockPos(x, clearance.minY() - 1, z);
                while (fill.contains(anchor)) anchor = anchor.below();
                if (!level.isLoaded(anchor) || !level.getFluidState(anchor).isEmpty()
                        || level.getBlockState(anchor).hasBlockEntity()
                        || !level.getBlockState(anchor).isFaceSturdy(level, anchor, Direction.UP)) {
                    return new ConstructionPlanner.Plan(tasks, 1, 0);
                }
            }
        }
        int unsafe = 0;
        for (BlockPos pos : BlockPos.betweenClosed(clearance.minX(), clearance.minY(), clearance.minZ(),
                clearance.maxX(), clearance.maxY(), clearance.maxZ())) {
            BlockState state = level.getBlockState(pos);
            if (state.isAir()) continue;
            if (!safeToChange(level, pos, state)) unsafe++;
            else tasks.add(new ConstructionOperation(pos.immutable(), Blocks.AIR.defaultBlockState(), ConstructionOperation.Action.REMOVE));
        }
        for (BlockPos pos : fill) {
            BlockState state = level.getBlockState(pos);
            if (safeToChange(level, pos, state) && state.isFaceSturdy(level, pos, Direction.UP)) continue;
            if (!safeToChange(level, pos, state)) unsafe++;
            else tasks.add(new ConstructionOperation(pos, Blocks.DIRT.defaultBlockState(), state.isAir()
                    ? ConstructionOperation.Action.PLACE : ConstructionOperation.Action.REPLACE));
        }
        return new ConstructionPlanner.Plan(tasks, unsafe, 0);
    }

    private boolean available(ServerLevel level) {
        BoundingBox box = reservedBounds();
        BlockPos min = new BlockPos(box.minX(), box.minY() - 1, box.minZ());
        BlockPos max = new BlockPos(box.maxX(), box.maxY(), box.maxZ());
        return level.isInWorldBounds(min) && level.isInWorldBounds(max) && level.hasChunksAt(min, max)
                && level.getWorldBorder().isWithinBounds(min) && level.getWorldBorder().isWithinBounds(max);
    }

    static boolean safeToChange(ServerLevel level, BlockPos pos, BlockState state) {
        return state.getFluidState().isEmpty() && !state.hasBlockEntity() && state.getDestroySpeed(level, pos) >= 0;
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("Dimension", dimension.toString());
        tag.putIntArray("Clearance", new int[]{clearance.minX(), clearance.minY(), clearance.minZ(),
                clearance.maxX(), clearance.maxY(), clearance.maxZ()});
        tag.putIntArray("Fill", fill.stream().flatMapToInt(pos -> java.util.stream.IntStream.of(
                pos.getX(), pos.getY(), pos.getZ())).toArray());
        return tag;
    }

    public static BuildingSitePlan load(CompoundTag tag) {
        ResourceLocation dimension = ResourceLocation.tryParse(tag.getString("Dimension"));
        int[] box = tag.getIntArray("Clearance");
        int[] positions = tag.getIntArray("Fill");
        if (dimension == null || box.length != 6 || box[0] > box[3] || box[1] > box[4] || box[2] > box[5]
                || positions.length % 3 != 0 || positions.length > 12288) {
            throw new IllegalArgumentException("Invalid building site data");
        }
        Set<BlockPos> fill = new LinkedHashSet<>();
        for (int i = 0; i < positions.length; i += 3) fill.add(new BlockPos(positions[i], positions[i + 1], positions[i + 2]));
        return new BuildingSitePlan(dimension, new BoundingBox(box[0], box[1], box[2], box[3], box[4], box[5]), fill);
    }
}
