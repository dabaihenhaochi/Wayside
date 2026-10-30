package io.github.dabaihenhaochi.wayside.entity.task.build;

import io.github.dabaihenhaochi.wayside.entity.NpcEntity;
import io.github.dabaihenhaochi.wayside.entity.action.ActionResult;
import io.github.dabaihenhaochi.wayside.entity.task.TaskSequence;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Rotation;

/** 通用建造流程。步骤顺序由任务链负责，每个子任务只完成自己的职责。 */
public class BuildBuildingTask extends TaskSequence {
    public static final String ID = "wayside:build_building";
    private final BuildingProject project;

    public BuildBuildingTask() { this(BuildHutTask.BLUEPRINT, Rotation.NONE, 8, 2, 2); }

    public BuildBuildingTask(ResourceLocation blueprint, Rotation rotation, int siteRange, int heightRange, int earthworkDepth) {
        this(ID, new BuildingProject(blueprint, rotation, siteRange, heightRange, earthworkDepth));
    }

    protected BuildBuildingTask(String id, ResourceLocation blueprint, Rotation rotation,
                                int siteRange, int heightRange, int earthworkDepth) {
        this(id, new BuildingProject(blueprint, rotation, siteRange, heightRange, earthworkDepth));
    }

    private BuildBuildingTask(String id, BuildingProject project) {
        super(id,
                step("SITE", () -> new SelectBuildingSiteTask(project)),
                step("MATERIALS", () -> new PrepareBuildingMaterialsTask(project)),
                step("TERRAIN", () -> new PrepareBuildingSiteTask(project.site)),
                step("CONSTRUCTION", () -> new BuildStructureTask(project.building)));
        this.project = project;
    }

    @Override public ActionResult tick(NpcEntity npc) {
        if (!(npc.level() instanceof ServerLevel level) || !project.bind(level)) return ActionResult.failed(ActionResult.Failure.INVALID_TARGET);
        if (!currentStepName().equals("COMPLETED") && project.blueprint(level) == null) return ActionResult.failed(ActionResult.Failure.INVALID_TARGET);
        // 后续步骤必须已有场地；坏存档不能从错误阶段重新清空建筑。
        if (!currentStepName().equals("SITE")
                && !currentStepName().equals("COMPLETED") && !project.hasSite()) return ActionResult.failed(ActionResult.Failure.INVALID_TARGET);
        return super.tick(npc);
    }

    @Override public CompoundTag save() {
        CompoundTag tag = super.save();
        tag.put("Project", project.save());
        return tag;
    }

    @Override public void load(CompoundTag tag) {
        // 上一轮保存过独立蓝图步骤；合并初始化后，对应索引前移一格。
        if (tag.getCompound("Steps").contains("BLUEPRINT")) {
            tag = tag.copy();
            tag.putInt("Current", Math.max(0, tag.getInt("Current") - 1));
            tag.getCompound("Steps").remove("BLUEPRINT");
        }
        project.load(tag.getCompound("Project"));
        super.load(tag);
    }
}
