package io.github.dabaihenhaochi.wayside.building;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

import java.util.Objects;
import java.util.UUID;

public record BuildingInstance(
        UUID id,
        ResourceLocation blueprintId,
        BlockPos origin,
        Rotation rotation
) {
    public BuildingInstance {
        Objects.requireNonNull(id);
        Objects.requireNonNull(blueprintId);
        Objects.requireNonNull(origin);
        Objects.requireNonNull(rotation);

        origin = origin.immutable();
    }

    public BlockPos toWorldPos(BlockPos relativePos) {
        return StructureTemplate.transform(
                relativePos,
                Mirror.NONE,
                rotation,
                BlockPos.ZERO
        ).offset(origin);
    }

    public BoundingBox getBoundingBox(BuildingBlueprint blueprint) {
        if (!blueprint.getId().equals(blueprintId)) {
            throw new IllegalArgumentException("Blueprint ID mismatch");
        }

        StructurePlaceSettings settings = new StructurePlaceSettings()
                .setMirror(Mirror.NONE)
                .setRotation(rotation)
                .setRotationPivot(BlockPos.ZERO);

        return blueprint.getTemplate().getBoundingBox(settings, origin);
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("Building", id);
        tag.putString("Blueprint", blueprintId.toString());
        tag.putIntArray("Origin", new int[]{origin.getX(), origin.getY(), origin.getZ()});
        tag.putString("Rotation", rotation.name());
        return tag;
    }

    public static BuildingInstance load(CompoundTag tag) {
        ResourceLocation blueprint = ResourceLocation.tryParse(tag.getString("Blueprint"));
        int[] origin = tag.getIntArray("Origin");
        if (blueprint == null || origin.length != 3 || !tag.hasUUID("Building")) {
            throw new IllegalArgumentException("Invalid building instance");
        }
        return new BuildingInstance(tag.getUUID("Building"), blueprint,
                new BlockPos(origin[0], origin[1], origin[2]), Rotation.valueOf(tag.getString("Rotation")));
    }
}
