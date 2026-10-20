package io.github.dabaihenhaochi.wayside.entity.task;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/** Explicit task factory; not a Minecraft registry. */
public final class NpcTaskTypes {
    private static final Map<String, Supplier<? extends NpcTask>> TYPES = new HashMap<>();

    static {
        register(GatherAndCraftPlanksTask.ID, GatherAndCraftPlanksTask::new);
        register(VisitPositionTask.ID, VisitPositionTask::new);
    }

    private NpcTaskTypes() {}

    public static void register(String id, Supplier<? extends NpcTask> factory) {
        if (TYPES.putIfAbsent(id, factory) != null) {
            throw new IllegalArgumentException("Duplicate NPC task type: " + id);
        }
    }

    public static Optional<NpcTask> create(String id) {
        Supplier<? extends NpcTask> factory = TYPES.get(id);
        return factory == null ? Optional.empty() : Optional.of(factory.get());
    }
}
