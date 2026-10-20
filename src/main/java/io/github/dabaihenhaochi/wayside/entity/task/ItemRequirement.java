package io.github.dabaihenhaochi.wayside.entity.task;

import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;

import java.util.Objects;

/**
 * Inventory target, not a request to craft this many additional items.
 * Existing matching stacks count toward the target.
 */
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

    public int missing(SimpleContainer inventory) {
        return Math.max(0, totalCount - count(inventory));
    }
}
