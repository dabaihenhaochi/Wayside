package io.github.dabaihenhaochi.wayside.entity.task.build;

import io.github.dabaihenhaochi.wayside.building.BuildingInstance;
import io.github.dabaihenhaochi.wayside.building.BuildingManager;
import io.github.dabaihenhaochi.wayside.building.BuildingMaterialCalculator;
import io.github.dabaihenhaochi.wayside.building.BuildingSitePlanner;
import io.github.dabaihenhaochi.wayside.building.ConstructionPlanner;
import io.github.dabaihenhaochi.wayside.entity.NpcEntity;
import io.github.dabaihenhaochi.wayside.entity.action.ActionResult;
import io.github.dabaihenhaochi.wayside.entity.task.NpcTask;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** 分批选址、检查真实施工入口并登记场地；选址结果交给后续子任务。 */
final class SelectBuildingSiteTask implements NpcTask {
    private static final int SITES_PER_TICK = 2;
    private final BuildingProject project;
    private List<BlockPos> candidates;
    private int cursor;
    private ActionResult.Failure blockedBy;

    SelectBuildingSiteTask(BuildingProject project) { this.project = project; }
    @Override public String typeId() { return "wayside:select_building_site"; }
    @Override public String stageName() { return "SELECTING_SITE"; }
    @Override public void pause(NpcEntity npc) {} // 暂停保留游标；读档可重新做只读搜索。

    @Override public ActionResult tick(NpcEntity npc) {
        if (project.hasSite()) return ActionResult.success();
        if (!(npc.level() instanceof ServerLevel level)) return ActionResult.failed(ActionResult.Failure.INVALID_TARGET);
        if (!npc.onGround()) return ActionResult.running();
        var blueprint = project.blueprint(level);
        if (blueprint == null) return ActionResult.failed(ActionResult.Failure.INVALID_TARGET);
        if (candidates == null) {
            candidates = BuildingSitePlanner.candidates(npc.blockPosition(), project.siteRange, project.heightRange);
            cursor = 0;
            blockedBy = ActionResult.Failure.UNREACHABLE;
        }
        var manager = new BuildingManager(level);
        for (int attempts = 0; cursor < candidates.size() && attempts < SITES_PER_TICK; attempts++) {
            BlockPos origin = candidates.get(cursor++);
            var candidate = new BuildingInstance(UUID.randomUUID(), blueprint.getId(), origin, project.rotation);
            if (candidate.getBoundingBox(blueprint).isInside(npc.blockPosition())) continue;
            var site = BuildingSitePlanner.plan(level, blueprint, candidate, project.earthworkDepth, project.earthworkDepth).orElse(null);
            if (site == null || !manager.isAreaAvailable(site.reservedBounds())) continue;
            var terrain = site.remaining(level);
            // 整地会清空房屋空间，不能把将拆除的方块计作已完成的房屋。
            var house = ConstructionPlanner.createPlan(level, blueprint, candidate, ConstructionPlanner.AirPolicy.CLEAR,
                    pos -> Blocks.AIR.defaultBlockState());
            if (!supportsMaterials(terrain, house) || !hasEntrance(npc, terrain, house)) continue;
            var registered = manager.tryRegister(blueprint, origin, project.rotation, site.reservedBounds());
            if (registered.isPresent()) {
                project.building = registered.get();
                project.site = site;
                candidates = null;
                return ActionResult.success();
            }
        }
        if (cursor < candidates.size()) return ActionResult.running();
        candidates = null; // 明确重试时重新从 NPC 所在位置搜索。
        return ActionResult.failed(blockedBy);
    }

    private boolean supportsMaterials(ConstructionPlanner.Plan terrain, ConstructionPlanner.Plan house) {
        var total = new ArrayList<>(terrain.tasks());
        total.addAll(house.tasks());
        var cost = BuildingMaterialCalculator.calculate(new ConstructionPlanner.Plan(total,
                terrain.unavailablePositions() + house.unavailablePositions(),
                terrain.uncheckedBlockEntities() + house.uncheckedBlockEntities()));
        if (!cost.canSupply()) { blockedBy = ActionResult.Failure.INVALID_TARGET; return false; }
        return true;
    }

    private static boolean hasEntrance(NpcEntity npc, ConstructionPlanner.Plan terrain, ConstructionPlanner.Plan house) {
        var preview = new ConstructionWorkSelector(!terrain.isComplete());
        preview.prepare(terrain.isComplete() ? house.tasks() : terrain.tasks(), npc);
        return preview.next(npc) != null;
    }
}
