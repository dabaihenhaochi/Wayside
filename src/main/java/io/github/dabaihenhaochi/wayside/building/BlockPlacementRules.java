package io.github.dabaihenhaochi.wayside.building;

import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.SlabType;

import java.util.Objects;
import java.util.Optional;

/** 建筑计价与单格放置共用的基础规则；不枚举具体方块名称。 */
public final class BlockPlacementRules {
    private BlockPlacementRules() {}

    /**
     * 返回完成该单格状态需要的物品及数量。
     * 多格联动、方块实体和含液体状态暂由专门处理器负责。
     * 自定义模组的额外放置行为仍需另行适配。
     */
    public static Optional<ItemStack> costFor(BlockState state) {
        Objects.requireNonNull(state);
        Item item = state.getBlock().asItem();
        if (state.isAir() || item == Items.AIR
                || !(item instanceof BlockItem blockItem)
                || blockItem.getBlock() != state.getBlock()
                || state.hasBlockEntity()
                || !state.getFluidState().isEmpty()
                || state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
                || state.hasProperty(BlockStateProperties.BED_PART)) {
            return Optional.empty();
        }

        int count = 1;
        if (state.hasProperty(BlockStateProperties.SLAB_TYPE)
                && state.getValue(BlockStateProperties.SLAB_TYPE) == SlabType.DOUBLE) {
            count = 2;
        } else if (state.hasProperty(BlockStateProperties.LAYERS)) {
            count = state.getValue(BlockStateProperties.LAYERS);
        } else if (state.hasProperty(BlockStateProperties.CANDLES)) {
            count = state.getValue(BlockStateProperties.CANDLES);
        } else if (state.hasProperty(BlockStateProperties.EGGS)) {
            count = state.getValue(BlockStateProperties.EGGS);
        } else if (state.hasProperty(BlockStateProperties.PICKLES)) {
            count = state.getValue(BlockStateProperties.PICKLES);
        }
        return Optional.of(new ItemStack(item, count));
    }
}
