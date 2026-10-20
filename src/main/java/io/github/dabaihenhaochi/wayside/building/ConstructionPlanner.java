
package io.github.dabaihenhaochi.wayside.building;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

import java.util.ArrayList;
import java.util.List;

public final class ConstructionPlanner {

    private ConstructionPlanner() {
    }

    public enum AirPolicy {
        IGNORE,
        CLEAR
    }

    public record Plan(
            List<ConstructionTask> tasks,
            int unavailablePositions,
            int uncheckedBlockEntities
    ) {
        public Plan {
            tasks = List.copyOf(tasks);
        }

        public boolean isComplete() {
            return tasks.isEmpty()
                    && unavailablePositions == 0
                    && uncheckedBlockEntities == 0;
        }
    }

    public static Plan createPlan(
            ServerLevel level,
            BuildingBlueprint blueprint,
            BuildingInstance building,
            AirPolicy airPolicy
    ) {
        if (!blueprint.getId().equals(building.blueprintId())) {
            throw new IllegalArgumentException("Blueprint ID mismatch");
        }

        List<ConstructionTask> tasks = new ArrayList<>();
        int unavailable = 0;
        int uncheckedBlockEntities = 0;

        for (StructureTemplate.StructureBlockInfo info
                : blueprint.getBlocks()) {

            BlockState templateState = info.state();

            // 结构空位不是需要放置的方块。
            if (templateState.is(Blocks.STRUCTURE_VOID)) {
                continue;
            }

            // 根据施工策略，决定是否清理蓝图中的空气区域。
            if (templateState.isAir()
                    && airPolicy == AirPolicy.IGNORE) {
                continue;
            }

            BlockPos worldPos = building.toWorldPos(info.pos());

            // 不读取无效坐标或未加载的区块。
            if (!level.isInWorldBounds(worldPos)
                    || !level.isLoaded(worldPos)) {
                unavailable++;
                continue;
            }

            // 根据建筑朝向，旋转方块状态。
            BlockState targetState = templateState.rotate(
                    level,
                    worldPos,
                    building.rotation()
            );

            BlockState currentState = level.getBlockState(worldPos);

            // 方块实体的数据暂时不参与完成判定。
            if (info.nbt() != null) {
                uncheckedBlockEntities++;
            }

            if (currentState.equals(targetState)) {
                continue;
            }

            ConstructionTask.Action action;

            if (targetState.isAir()) {
                action = ConstructionTask.Action.REMOVE;
            } else if (currentState.isAir()) {
                action = ConstructionTask.Action.PLACE;
            } else {
                action = ConstructionTask.Action.REPLACE;
            }

            tasks.add(new ConstructionTask(
                    worldPos,
                    targetState,
                    action
            ));
        }

        return new Plan(
                tasks,
                unavailable,
                uncheckedBlockEntities
        );
    }
}
