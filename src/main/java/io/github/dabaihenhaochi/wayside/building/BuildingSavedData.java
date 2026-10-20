
package io.github.dabaihenhaochi.wayside.building;

import io.github.dabaihenhaochi.wayside.Wayside;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class BuildingSavedData extends SavedData {

    private static final String FILE_NAME = "wayside_buildings";

    private static final Factory<BuildingSavedData> FACTORY =
            new Factory<>(
                    BuildingSavedData::new,
                    BuildingSavedData::load
            );

    private final Map<UUID, Entry> buildings = new LinkedHashMap<>();

    public record Entry(
            BuildingInstance instance,
            BoundingBox bounds
    ) {
    }

    public static BuildingSavedData get(ServerLevel level) {
        return level.getDataStorage()
                .computeIfAbsent(FACTORY, FILE_NAME);
    }

    public List<Entry> getEntries() {
        return List.copyOf(buildings.values());
    }

    public int size() {
        return buildings.size();
    }

    public void add(BuildingInstance instance, BoundingBox bounds) {
        if (buildings.containsKey(instance.id())) {
            throw new IllegalArgumentException("Duplicate building ID");
        }

        buildings.put(instance.id(), new Entry(instance, bounds));
        setDirty();
    }

    public boolean remove(UUID id) {
        if (buildings.remove(id) != null) {
            setDirty();
            return true;
        }
        return false;
    }

    @Override
    public CompoundTag save(
            CompoundTag tag,
            HolderLookup.Provider registries
    ) {
        ListTag list = new ListTag();

        for (Entry entry : buildings.values()) {
            BuildingInstance instance = entry.instance();
            BoundingBox bounds = entry.bounds();

            CompoundTag buildingTag = new CompoundTag();

            buildingTag.putUUID("Id", instance.id());
            buildingTag.putString(
                    "Blueprint",
                    instance.blueprintId().toString()
            );
            buildingTag.putString(
                    "Rotation",
                    instance.rotation().name()
            );

            BlockPos pos = instance.origin();
            buildingTag.putIntArray("Origin", new int[]{
                    pos.getX(), pos.getY(), pos.getZ()
            });

            buildingTag.putIntArray("Bounds", new int[]{
                    bounds.minX(), bounds.minY(), bounds.minZ(),
                    bounds.maxX(), bounds.maxY(), bounds.maxZ()
            });

            list.add(buildingTag);
        }

        tag.put("Buildings", list);
        return tag;
    }

    private static BuildingSavedData load(
            CompoundTag tag,
            HolderLookup.Provider registries
    ) {
        BuildingSavedData data = new BuildingSavedData();

        ListTag list = tag.getList(
                "Buildings",
                Tag.TAG_COMPOUND
        );

        for (int i = 0; i < list.size(); i++) {
            CompoundTag buildingTag = list.getCompound(i);

            try {
                if (!buildingTag.hasUUID("Id")) {
                    throw new IllegalArgumentException("Missing ID");
                }

                UUID id = buildingTag.getUUID("Id");
                ResourceLocation blueprintId = ResourceLocation.tryParse(
                        buildingTag.getString("Blueprint")
                );

                if (blueprintId == null) {
                    throw new IllegalArgumentException("Invalid blueprint ID");
                }
                Rotation rotation = Rotation.valueOf(
                        buildingTag.getString("Rotation")
                );

                int[] pos = buildingTag.getIntArray("Origin");
                int[] box = buildingTag.getIntArray("Bounds");

                if (pos.length != 3 || box.length != 6) {
                    throw new IllegalArgumentException("Invalid coordinates");
                }

                if (box[0] > box[3]
                        || box[1] > box[4]
                        || box[2] > box[5]) {
                    throw new IllegalArgumentException("Invalid bounds");
                }

                BuildingInstance instance = new BuildingInstance(
                        id,
                        blueprintId,
                        new BlockPos(pos[0], pos[1], pos[2]),
                        rotation
                );

                BoundingBox bounds = new BoundingBox(
                        box[0], box[1], box[2],
                        box[3], box[4], box[5]
                );

                if (data.buildings.containsKey(id)) {
                    throw new IllegalArgumentException("Duplicate building ID");
                }

                data.buildings.put(id, new Entry(instance, bounds));

            } catch (IllegalArgumentException ex) {
                Wayside.LOGGER.error(
                        "[Wayside] 无法读取第 {} 条建筑存档",
                        i,
                        ex
                );
            }
        }

        return data;
    }
}
