package io.github.dabaihenhaochi.wayside.entity.task.build;

import io.github.dabaihenhaochi.wayside.building.BuildingBlueprint;
import io.github.dabaihenhaochi.wayside.building.BuildingInstance;
import io.github.dabaihenhaochi.wayside.building.BuildingSitePlan;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Rotation;

import java.util.Objects;

/** 一次建造流程共享的输入和选址结果，不推进任务或执行动作。 */
final class BuildingProject {
    ResourceLocation blueprintId;
    Rotation rotation;
    int siteRange;
    int heightRange;
    int earthworkDepth;
    ResourceLocation dimension;
    BuildingInstance building;
    BuildingSitePlan site;
    private BuildingBlueprint blueprint;
    private boolean valid = true;

    BuildingProject(ResourceLocation blueprintId, Rotation rotation, int siteRange, int heightRange, int earthworkDepth) {
        this.blueprintId = Objects.requireNonNull(blueprintId);
        this.rotation = Objects.requireNonNull(rotation);
        if (siteRange < 0 || heightRange < 0 || earthworkDepth < 0 || earthworkDepth > 8) throw new IllegalArgumentException("Invalid site range");
        this.siteRange = siteRange;
        this.heightRange = heightRange;
        this.earthworkDepth = earthworkDepth;
    }

    boolean bind(ServerLevel level) {
        if (dimension == null) dimension = level.dimension().location();
        return valid && dimension.equals(level.dimension().location());
    }

    BuildingBlueprint blueprint(ServerLevel level) {
        if (blueprint == null) {
            try { blueprint = BuildingBlueprint.load(level, blueprintId).orElse(null); }
            catch (IllegalArgumentException ignored) { return null; }
        }
        return blueprint;
    }

    boolean hasSite() { return building != null && site != null; }

    CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("Blueprint", blueprintId.toString());
        tag.putString("Rotation", rotation.name());
        tag.putInt("SiteRange", siteRange);
        tag.putInt("HeightRange", heightRange);
        tag.putInt("EarthworkDepth", earthworkDepth);
        if (dimension != null) tag.putString("Dimension", dimension.toString());
        if (building != null) tag.put("Building", building.save());
        if (site != null) tag.put("Site", site.save());
        tag.putBoolean("Valid", valid);
        return tag;
    }

    void load(CompoundTag tag) {
        blueprint = null;
        building = null;
        site = null;
        dimension = null;
        valid = false;
        try {
            blueprintId = Objects.requireNonNull(ResourceLocation.tryParse(tag.getString("Blueprint")));
            rotation = Rotation.valueOf(tag.getString("Rotation"));
            siteRange = tag.getInt("SiteRange");
            heightRange = tag.getInt("HeightRange");
            earthworkDepth = tag.getInt("EarthworkDepth");
            if (siteRange < 0 || heightRange < 0 || earthworkDepth < 0 || earthworkDepth > 8) return;
            if (tag.contains("Dimension", Tag.TAG_STRING)) {
                dimension = ResourceLocation.tryParse(tag.getString("Dimension"));
                if (dimension == null) return;
            }
            if (tag.contains("Building", Tag.TAG_COMPOUND)) building = BuildingInstance.load(tag.getCompound("Building"));
            if (tag.contains("Site", Tag.TAG_COMPOUND)) site = BuildingSitePlan.load(tag.getCompound("Site"));
            if ((building == null) != (site == null)) return;
            if (building != null && (!building.blueprintId().equals(blueprintId) || !site.dimension().equals(dimension))) return;
            valid = tag.getBoolean("Valid");
        } catch (IllegalArgumentException | NullPointerException ignored) {
            // 无效数据交给父任务的 tick 上报，不能重新选址后重复施工。
        }
    }
}
