package io.github.dabaihenhaochi.wayside.entity.task.resource;

import net.minecraft.world.SimpleContainer;
import io.github.dabaihenhaochi.wayside.entity.inventory.InventoryOps;
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

    // 汇总所有匹配标签的物品堆数量。
    public int count(SimpleContainer inventory) { return InventoryOps.count(inventory, itemTag); }

    // 计算距离目标总量还缺多少，最低为零。
    public int missing(SimpleContainer inventory) {
        return Math.max(0, totalCount - count(inventory));
    }
}
