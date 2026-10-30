
package io.github.dabaihenhaochi.wayside.building;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class BuildingManager {

    private final BuildingSavedData data;

    public BuildingManager(ServerLevel level) {
        this.data = BuildingSavedData.get(level);
    }

    public Optional<BuildingInstance> tryRegister(
            BuildingBlueprint blueprint,
            BlockPos origin,
            Rotation rotation
    ) {
        return tryRegister(blueprint, origin, rotation, null);
    }

    /** 整地时一起预留地基空间，避免不同高度的建筑互相挖掉基础。 */
    public Optional<BuildingInstance> tryRegister(BuildingBlueprint blueprint, BlockPos origin,
                                                  Rotation rotation, BoundingBox reservation) {
        BuildingInstance candidate = new BuildingInstance(
                UUID.randomUUID(),
                blueprint.getId(),
                origin,
                rotation
        );

        BoundingBox candidateBox =
                candidate.getBoundingBox(blueprint);
        if (reservation != null) {
            if (!reservation.isInside(new BlockPos(candidateBox.minX(), candidateBox.minY(), candidateBox.minZ()))
                    || !reservation.isInside(new BlockPos(candidateBox.maxX(), candidateBox.maxY(), candidateBox.maxZ()))) {
                throw new IllegalArgumentException("Reservation does not contain building");
            }
            candidateBox = reservation;
        }

        if (!isAreaAvailable(candidateBox)) return Optional.empty();

        data.add(candidate, candidateBox);

        return Optional.of(candidate);
    }

    public boolean isAreaAvailable(BoundingBox bounds) {
        return data.getEntries().stream().noneMatch(existing -> existing.bounds().intersects(bounds));
    }

    /** 高频的垫高许可检查直接查询坐标，不复制全部建筑记录或创建临时包围盒。 */
    public boolean isPositionAvailable(BlockPos pos) {
        return !data.isPositionReserved(pos);
    }

    public boolean remove(UUID id) {
        return data.remove(id);
    }

    public int size() {
        return data.size();
    }

    public List<BuildingInstance> getBuildings() {
        return data.getEntries().stream()
                .map(BuildingSavedData.Entry::instance)
                .toList();
    }
}
