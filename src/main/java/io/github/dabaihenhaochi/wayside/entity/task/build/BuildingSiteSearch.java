package io.github.dabaihenhaochi.wayside.entity.task.build;

import io.github.dabaihenhaochi.wayside.building.BuildingBlueprint;
import io.github.dabaihenhaochi.wayside.building.BuildingInstance;
import io.github.dabaihenhaochi.wayside.building.BuildingManager;
import io.github.dabaihenhaochi.wayside.building.BuildingMaterialCalculator;
import io.github.dabaihenhaochi.wayside.building.BuildingSitePlan;
import io.github.dabaihenhaochi.wayside.building.BuildingSitePlanner;
import io.github.dabaihenhaochi.wayside.building.ConstructionPlanner;
import io.github.dabaihenhaochi.wayside.entity.NpcEntity;
import io.github.dabaihenhaochi.wayside.entity.action.ActionResult;
import io.github.dabaihenhaochi.wayside.entity.inventory.InventoryOps;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** NPC 的分批选址搜索：组合地形规划、总预算、入口预检和登记，不执行整地或建屋。 */
final class BuildingSiteSearch {
    private static final int SITES_PER_TICK = 2;
    private final BuildingBlueprint blueprint;
    private final List<BlockPos> candidates;
    private final Rotation rotation;
    private final int earthworkDepth;
    private int cursor;
    private ActionResult.Failure blockedBy = ActionResult.Failure.UNREACHABLE;

    record Selection(BuildingInstance building, BuildingSitePlan site) {}

    BuildingSiteSearch(BuildingBlueprint blueprint, BlockPos start, Rotation rotation,
                       int radius, int heightRange, int earthworkDepth) {
        this.blueprint = blueprint;
        this.candidates = BuildingSitePlanner.candidates(start, radius, heightRange);
        this.rotation = rotation;
        this.earthworkDepth = earthworkDepth;
    }

    Selection next(NpcEntity npc) {
        ServerLevel level = (ServerLevel) npc.level();
        BuildingManager manager = new BuildingManager(level);
        for (int attempts = 0; !exhausted() && attempts < SITES_PER_TICK; attempts++) {
            BlockPos origin = candidates.get(cursor++);
            BuildingInstance candidate = new BuildingInstance(UUID.randomUUID(), blueprint.getId(), origin, rotation);
            if (candidate.getBoundingBox(blueprint).isInside(npc.blockPosition())) continue;
            var site = BuildingSitePlanner.plan(level, blueprint, candidate, earthworkDepth, earthworkDepth).orElse(null);
            if (site == null || !manager.isAreaAvailable(site.reservedBounds())) continue;
            var terrain = site.remaining(level);
            // 按整地后的现场预算，不把即将拆除的旧方块计作已完成的房屋。
            var house = ConstructionPlanner.createPlan(level, blueprint, candidate, ConstructionPlanner.AirPolicy.CLEAR,
                    pos -> Blocks.AIR.defaultBlockState());
            if (!hasMaterials(npc, terrain, house) || !hasEntrance(npc, candidate, terrain, house)) continue;
            var registered = manager.tryRegister(blueprint, origin, rotation, site.reservedBounds());
            if (registered.isPresent()) return new Selection(registered.get(), site);
        }
        return null;
    }

    private boolean hasMaterials(NpcEntity npc, ConstructionPlanner.Plan terrain, ConstructionPlanner.Plan house) {
        var total = new ArrayList<>(terrain.tasks());
        total.addAll(house.tasks());
        var cost = BuildingMaterialCalculator.calculate(new ConstructionPlanner.Plan(total,
                terrain.unavailablePositions() + house.unavailablePositions(),
                terrain.uncheckedBlockEntities() + house.uncheckedBlockEntities()));
        if (!cost.canSupply()) { blockedBy = ActionResult.Failure.INVALID_TARGET; return false; }
        for (var entry : cost.requiredItems().entrySet()) {
            if (InventoryOps.count(npc.getInventory(), entry.getKey()) < entry.getValue()) {
                blockedBy = ActionResult.Failure.MISSING_INGREDIENT;
                return false;
            }
        }
        return true;
    }

    private static boolean hasEntrance(NpcEntity npc, BuildingInstance candidate,
                                       ConstructionPlanner.Plan terrain, ConstructionPlanner.Plan house) {
        var preview = new ConstructionWorkSelector(!terrain.isComplete());
        preview.prepare(terrain.isComplete() ? house.tasks() : terrain.tasks(), npc, candidate.origin().getY());
        return preview.next(npc) != null;
    }

    boolean exhausted() { return cursor >= candidates.size(); }
    ActionResult.Failure blockedBy() { return blockedBy; }
}
