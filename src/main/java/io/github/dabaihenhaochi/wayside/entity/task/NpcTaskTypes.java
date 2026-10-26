package io.github.dabaihenhaochi.wayside.entity.task;

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
}
