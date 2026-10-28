package io.github.dabaihenhaochi.wayside.entity.task.build;

import io.github.dabaihenhaochi.wayside.entity.task.NpcTask;

import io.github.dabaihenhaochi.wayside.building.BuildingBlueprint;
import io.github.dabaihenhaochi.wayside.entity.NpcEntity;
import io.github.dabaihenhaochi.wayside.entity.action.ActionResult;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Rotation;

/** 小屋场景的编排：读取现有背包 → 选址与总预算 → 整地 → 通用蓝图施工。 */
public final class BuildHutTask implements NpcTask {
    public static final String ID = "wayside:build_hut";
    private static final ResourceLocation BLUEPRINT = ResourceLocation.fromNamespaceAndPath("wayside", "test_house");
    private static final int SITE_RANGE = 8;
    private static final int HEIGHT_RANGE = 2;
    private static final int EARTHWORK_DEPTH = 2;
    private BuildStructureTask construction;
    private PrepareBuildingSiteTask preparation;
    private BuildingSiteSearch search;
    private ResourceLocation dimension;
    private boolean prepared;

    private NpcTask activeTask() { return prepared ? construction : preparation; }
    @Override public String typeId() { return ID; }
    @Override public String stageName() { return activeTask() == null ? "SELECTING_SITE" : activeTask().stageName(); }
    @Override public void pause(NpcEntity npc) { if (activeTask() != null) activeTask().pause(npc); }
    @Override public BlockPos activeBlockTarget() { return activeTask() == null ? null : activeTask().activeBlockTarget(); }
    @Override public BlockPos activeMoveTarget() { return activeTask() == null ? null : activeTask().activeMoveTarget(); }

    @Override public ActionResult tick(NpcEntity npc) {
        if (!(npc.level() instanceof ServerLevel level)) return ActionResult.failed(ActionResult.Failure.INVALID_TARGET);
        if (dimension == null) dimension = level.dimension().location();
        if (!dimension.equals(level.dimension().location())) return ActionResult.failed(ActionResult.Failure.INVALID_TARGET);
        if (construction != null) {
            if (!prepared) {
                if (preparation == null) return ActionResult.failed(ActionResult.Failure.INVALID_TARGET);
                ActionResult result = preparation.tick(npc);
                if (!result.isSuccess()) return result;
                preparation.pause(npc);
                prepared = true;
                return ActionResult.running(); // 下一 tick 才开始建筑，整地阶段绝不放房屋方块。
            }
            return construction.tick(npc);
        }
        if (!npc.onGround()) return ActionResult.running();
        if (search == null) {
            BuildingBlueprint blueprint;
            try { blueprint = BuildingBlueprint.load(level, BLUEPRINT).orElse(null); }
            catch (IllegalArgumentException e) { return ActionResult.failed(ActionResult.Failure.INVALID_TARGET); }
            if (blueprint == null) return ActionResult.failed(ActionResult.Failure.INVALID_TARGET);
            search = new BuildingSiteSearch(blueprint, npc.blockPosition(), Rotation.NONE,
                    SITE_RANGE, HEIGHT_RANGE, EARTHWORK_DEPTH);
        }
        var selected = search.next(npc);
        if (selected != null) {
            construction = new BuildStructureTask(selected.building());
            preparation = new PrepareBuildingSiteTask(selected.site());
            prepared = false;
            search = null;
            return ActionResult.running();
        }
        if (!search.exhausted()) return ActionResult.running();
        ActionResult result = ActionResult.failed(search.blockedBy());
        search = null;
        return result;
    }

    @Override public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        if (dimension != null) tag.putString("Dimension", dimension.toString());
        if (construction != null) tag.put("Construction", construction.save());
        if (preparation != null) tag.put("Preparation", preparation.save());
        tag.putBoolean("Prepared", prepared);
        return tag;
    }
    @Override public void load(CompoundTag tag) {
        construction = null;
        preparation = null;
        search = null;
        dimension = tag.contains("Dimension", Tag.TAG_STRING) ? ResourceLocation.tryParse(tag.getString("Dimension")) : null;
        // 兼容旧的自动建屋存档：旧施工已开始，不能读档后再清空房屋。
        prepared = tag.contains("Prepared", Tag.TAG_BYTE) ? tag.getBoolean("Prepared")
                : tag.contains("Construction", Tag.TAG_COMPOUND) && !tag.contains("Preparation", Tag.TAG_COMPOUND);
        if (tag.contains("Construction", Tag.TAG_COMPOUND)) {
            construction = new BuildStructureTask();
            construction.load(tag.getCompound("Construction"));
        }
        if (tag.contains("Preparation", Tag.TAG_COMPOUND)) {
            preparation = new PrepareBuildingSiteTask();
            preparation.load(tag.getCompound("Preparation"));
        }
    }
}
