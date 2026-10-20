
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
        BuildingInstance candidate = new BuildingInstance(
                UUID.randomUUID(),
                blueprint.getId(),
                origin,
                rotation
        );

        BoundingBox candidateBox =
                candidate.getBoundingBox(blueprint);

        for (BuildingSavedData.Entry existing : data.getEntries()) {
            if (existing.bounds().intersects(candidateBox)) {
                return Optional.empty();
            }
        }

        data.add(candidate, candidateBox);

        return Optional.of(candidate);
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
