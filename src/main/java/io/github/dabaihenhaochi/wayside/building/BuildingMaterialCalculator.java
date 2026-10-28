package io.github.dabaihenhaochi.wayside.building;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Converts pending construction operations into exact item requirements.
 *
 * This class does not read an inventory, decide how to make an item, or
 * mutate the world. It is the boundary between building and material planning.
 *
 * Single-position item costs are shared with the placement action.
 * Unsupported multi-block, composite, and special states are reported rather
 * than producing a misleading resource request.
 */
public final class BuildingMaterialCalculator {
    private BuildingMaterialCalculator() {}

    public record Result(
            Map<Item, Integer> requiredItems,
            List<ConstructionOperation> unsupported,
            int unavailablePositions,
            int uncheckedBlockEntities
    ) {
        public Result {
            requiredItems = Collections.unmodifiableMap(new LinkedHashMap<>(requiredItems));
            unsupported = List.copyOf(unsupported);
            if (unavailablePositions < 0 || uncheckedBlockEntities < 0) {
                throw new IllegalArgumentException("Invalid construction counts");
            }
        }

        /** The request can be handed to the material system without silent omissions. */
        public boolean canSupply() {
            return unsupported.isEmpty()
                    && unavailablePositions == 0
                    && uncheckedBlockEntities == 0;
        }
    }

    public static Result calculate(ConstructionPlanner.Plan construction) {
        Objects.requireNonNull(construction);
        Map<Item, Integer> items = new LinkedHashMap<>();
        List<ConstructionOperation> unsupported = new ArrayList<>();

        for (ConstructionOperation task : construction.tasks()) {
            // Removing an old block does not require a new item; its drops are
            // deliberately not counted as material we already own.
            if (task.action() == ConstructionOperation.Action.REMOVE) continue;

            BlockState state = task.targetState();
            ItemStack cost = BlockPlacementRules.costFor(state).orElse(null);
            if (cost == null) {
                unsupported.add(task);
                continue;
            }
            items.merge(cost.getItem(), cost.getCount(), Integer::sum);
        }

        return new Result(items, unsupported,
                construction.unavailablePositions(),
                construction.uncheckedBlockEntities());
    }

}

