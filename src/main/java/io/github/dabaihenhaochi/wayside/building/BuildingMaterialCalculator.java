package io.github.dabaihenhaochi.wayside.building;

import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.SlabType;

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
 * Only straightforward one-item/one-block placement is counted automatically.
 * Unsupported multi-block, composite, and special states are reported rather
 * than producing a misleading resource request.
 */
public final class BuildingMaterialCalculator {
    private BuildingMaterialCalculator() {}

    public record Result(
            Map<Item, Integer> requiredItems,
            List<ConstructionTask> unsupported,
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
        List<ConstructionTask> unsupported = new ArrayList<>();

        for (ConstructionTask task : construction.tasks()) {
            // Removing an old block does not require a new item; its drops are
            // deliberately not counted as material we already own.
            if (task.action() == ConstructionTask.Action.REMOVE) continue;

            BlockState state = task.targetState();
            Item item = state.getBlock().asItem();
            if (state.isAir()
                    || item == Items.AIR
                    || !(item instanceof BlockItem blockItem)
                    || blockItem.getBlock() != state.getBlock()
                    || requiresSpecialPlacement(state)) {
                unsupported.add(task);
                continue;
            }

            // PLACE and REPLACE both require the target's new material.
            items.merge(item, 1, Integer::sum);
        }

        return new Result(items, unsupported,
                construction.unavailablePositions(),
                construction.uncheckedBlockEntities());
    }

    private static boolean requiresSpecialPlacement(BlockState state) {
        // A door/bed occupies more than one block per item. Double slabs and
        // stacked snow also cannot be obtained by a single placement.
        if (state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
                || state.hasProperty(BlockStateProperties.BED_PART)
                || state.hasProperty(BlockStateProperties.LAYERS)) {
            return true;
        }
        if (state.hasProperty(BlockStateProperties.SLAB_TYPE)
                && state.getValue(BlockStateProperties.SLAB_TYPE) == SlabType.DOUBLE) {
            return true;
        }
        // A waterlogged block needs more than just the block item.
        return state.hasProperty(BlockStateProperties.WATERLOGGED)
                && state.getValue(BlockStateProperties.WATERLOGGED);
    }
}
