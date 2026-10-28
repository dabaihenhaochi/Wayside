package io.github.dabaihenhaochi.wayside.entity.task.resource;

import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;

import java.util.Objects;

// 按物品标签设定背包总量目标，已有库存也计入。
public record ItemRequirement(TagKey<Item> itemTag, int totalCount) {
    public ItemRequirement {
        Objects.requireNonNull(itemTag, "itemTag");
        if (totalCount <= 0) {
            throw new IllegalArgumentException("totalCount must be positive");
        }
    }

    public boolean matches(ItemStack stack) {
        return !stack.isEmpty() && stack.is(itemTag);
    }

    // 汇总所有匹配标签的物品堆数量。
    public int count(SimpleContainer inventory) {
        int count = 0;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (matches(stack)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    // 计算距离目标总量还缺多少，最低为零。
    public int missing(SimpleContainer inventory) {
        return Math.max(0, totalCount - count(inventory));
    }
}
