package io.github.dabaihenhaochi.wayside.entity.task.build;

import io.github.dabaihenhaochi.wayside.building.BuildingMaterialCalculator;
import io.github.dabaihenhaochi.wayside.building.ConstructionPlanner;
import io.github.dabaihenhaochi.wayside.entity.NpcEntity;
import io.github.dabaihenhaochi.wayside.entity.action.ActionResult;
import io.github.dabaihenhaochi.wayside.entity.task.CompositeTask;
import io.github.dabaihenhaochi.wayside.entity.task.NpcTask;
import io.github.dabaihenhaochi.wayside.entity.task.resource.SupplyMaterialsTask;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;

/** 按场地和房屋的总需求调用补给子任务，不在这里实现采集或配方。 */
final class PrepareBuildingMaterialsTask extends CompositeTask {
    private final BuildingProject project;
    private SupplyMaterialsTask supply;
    PrepareBuildingMaterialsTask(BuildingProject project) { this.project = project; }
    @Override public String typeId() { return "wayside:prepare_building_materials"; }
    @Override public String stageName() { return supply == null ? "CHECKING_MATERIALS" : supply.stageName(); }
    @Override protected NpcTask activeTask() { return supply; }

    @Override public ActionResult tick(NpcEntity npc) {
        if (!(npc.level() instanceof ServerLevel level) || !project.hasSite()) return ActionResult.failed(ActionResult.Failure.INVALID_TARGET);
        if (supply == null) {
            var blueprint = project.blueprint(level);
            if (blueprint == null) return ActionResult.failed(ActionResult.Failure.INVALID_TARGET);
            var terrain = project.site.remaining(level);
            // 整地会清空房屋空间，不能把即将拆除的现场方块算作已经建好。
            var house = ConstructionPlanner.createPlan(level, blueprint, project.building, ConstructionPlanner.AirPolicy.CLEAR,
                    pos -> Blocks.AIR.defaultBlockState());
            var operations = new ArrayList<>(terrain.tasks());
            operations.addAll(house.tasks());
            var cost = BuildingMaterialCalculator.calculate(new ConstructionPlanner.Plan(operations,
                    terrain.unavailablePositions() + house.unavailablePositions(),
                    terrain.uncheckedBlockEntities() + house.uncheckedBlockEntities()));
            if (!cost.canSupply()) return ActionResult.failed(ActionResult.Failure.INVALID_TARGET);
            if (cost.requiredItems().isEmpty()) return ActionResult.success();
            supply = new SupplyMaterialsTask(cost.requiredItems());
        }
        return supply.tick(npc);
    }

    // 补给计划由真实库存重算；场地结果已由建造上下文保存。
    @Override public void pause(NpcEntity npc) {
        super.pause(npc);
        supply = null;
    }
}
