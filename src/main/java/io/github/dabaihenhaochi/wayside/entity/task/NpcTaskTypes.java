package io.github.dabaihenhaochi.wayside.entity.task;

import io.github.dabaihenhaochi.wayside.entity.task.build.BuildHutTask;
import io.github.dabaihenhaochi.wayside.entity.task.build.BuildStructureTask;
import io.github.dabaihenhaochi.wayside.entity.task.build.PrepareBuildingSiteTask;
import io.github.dabaihenhaochi.wayside.entity.task.resource.EnsurePlanksTask;
import io.github.dabaihenhaochi.wayside.entity.task.resource.GatherAndCraftPlanksTask;
import io.github.dabaihenhaochi.wayside.entity.task.resource.SupplyMaterialsTask;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

// 按任务 ID 创建实例，独立于 Minecraft 注册表。
public final class NpcTaskTypes {
    private static final Map<String, Supplier<? extends NpcTask>> TYPES = new HashMap<>();

    static {
        register(GatherAndCraftPlanksTask.ID, GatherAndCraftPlanksTask::new);
        register(VisitPositionTask.ID, VisitPositionTask::new);
        register(EnsurePlanksTask.ID, EnsurePlanksTask::new);
        register(SupplyMaterialsTask.ID, SupplyMaterialsTask::new);
        register(BuildStructureTask.ID, BuildStructureTask::new);
        register(BuildHutTask.ID, BuildHutTask::new);
        register(PrepareBuildingSiteTask.ID, PrepareBuildingSiteTask::new);
    }

    private NpcTaskTypes() {}

    // 登记任务工厂，拒绝重复 ID。
    public static void register(String id, Supplier<? extends NpcTask> factory) {
        if (TYPES.putIfAbsent(id, factory) != null) {
            throw new IllegalArgumentException("Duplicate NPC task type: " + id);
        }
    }

    // 按 ID 新建任务，未知 ID 返回空。
    public static Optional<NpcTask> create(String id) {
        Supplier<? extends NpcTask> factory = TYPES.get(id);
        return factory == null ? Optional.empty() : Optional.of(factory.get());
    }

    // 兼容 v0.0.6 的阶段存档；具体任务类型知识集中在此处。
    public static Optional<NpcTask> restoreLegacy(CompoundTag entityTag) {
        if (!entityTag.contains("WaysideTaskStage", Tag.TAG_STRING)) return Optional.empty();
        return create(GatherAndCraftPlanksTask.ID).map(task -> {
            CompoundTag data = new CompoundTag();
            data.putString("Stage", entityTag.getString("WaysideTaskStage"));
            task.load(data);
            return task;
        });
    }
}
