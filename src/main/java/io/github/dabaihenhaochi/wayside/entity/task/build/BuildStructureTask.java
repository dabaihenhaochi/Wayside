package io.github.dabaihenhaochi.wayside.entity.task.build;

import io.github.dabaihenhaochi.wayside.entity.task.NpcTask;

import io.github.dabaihenhaochi.wayside.building.BuildingBlueprint;
import io.github.dabaihenhaochi.wayside.building.BuildingInstance;
import io.github.dabaihenhaochi.wayside.building.ConstructionPlanner;
import io.github.dabaihenhaochi.wayside.entity.NpcEntity;
import io.github.dabaihenhaochi.wayside.entity.action.ActionResult;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;

import java.util.Objects;

/** 通用蓝图任务：只负责加载蓝图、提供剩余计划和保存建筑身份。 */
public final class BuildStructureTask implements NpcTask {
    public static final String ID = "wayside:build_structure";
    private ConstructionExecutor executor = new ConstructionExecutor(false);
    private BuildingInstance building;
    private ConstructionPlanner.AirPolicy airPolicy = ConstructionPlanner.AirPolicy.CLEAR;
    private BuildingBlueprint blueprint;
    private ResourceLocation dimension;

    public BuildStructureTask() {}
    public BuildStructureTask(BuildingInstance building) { this(building, ConstructionPlanner.AirPolicy.CLEAR); }
    public BuildStructureTask(BuildingInstance building, ConstructionPlanner.AirPolicy policy) {
        this.building = Objects.requireNonNull(building);
        this.airPolicy = Objects.requireNonNull(policy);
        executor = new ConstructionExecutor(false);
    }
    @Override public String typeId() { return ID; }
    @Override public String stageName() { return executor.stageName(); }
    @Override public BlockPos activeBlockTarget() { return executor.activeBlockTarget(); }
    @Override public BlockPos activeMoveTarget() { return executor.activeMoveTarget(); }

    @Override public ActionResult tick(NpcEntity npc) {
        if (!(npc.level() instanceof ServerLevel level) || building == null) return ActionResult.failed(ActionResult.Failure.INVALID_TARGET);
        if (dimension == null) dimension = level.dimension().location();
        if (!dimension.equals(level.dimension().location())) return ActionResult.failed(ActionResult.Failure.INVALID_TARGET);
        if (blueprint == null) {
            try { blueprint = BuildingBlueprint.load(level, building.blueprintId()).orElse(null); }
            catch (IllegalArgumentException e) { return ActionResult.failed(ActionResult.Failure.INVALID_TARGET); }
            if (blueprint == null) return ActionResult.failed(ActionResult.Failure.INVALID_TARGET);
        }
        return executor.tick(npc, () -> ConstructionPlanner.createPlan(level, blueprint, building, airPolicy),
                pos -> !building.getBoundingBox(blueprint).isInside(pos));
    }
    @Override public void pause(NpcEntity npc) { executor.pause(npc); }

    @Override public CompoundTag save() {
        CompoundTag tag = building == null ? new CompoundTag() : building.save();
        if (dimension != null) tag.putString("Dimension", dimension.toString());
        tag.putString("AirPolicy", airPolicy.name());
        tag.put("Execution", executor.save());
        return tag;
    }
    @Override public void load(CompoundTag tag) {
        building = null;
        blueprint = null;
        dimension = tag.contains("Dimension", Tag.TAG_STRING) ? ResourceLocation.tryParse(tag.getString("Dimension")) : null;
        try {
            building = BuildingInstance.load(tag);
            airPolicy = ConstructionPlanner.AirPolicy.valueOf(tag.getString("AirPolicy"));
        } catch (IllegalArgumentException e) { building = null; }
        executor = new ConstructionExecutor(false);
        executor.load(tag.getCompound("Execution"));
    }
}
