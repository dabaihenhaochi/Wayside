package io.github.dabaihenhaochi.wayside.entity.inventory;

import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

//背包操作工具类。
public final class InventoryOps {

    // 工具类不需要创建对象。
    private InventoryOps() {
    }

    //统计背包中指定物品的总数量。不修改背包。
    public static int count(SimpleContainer inventory, Item item) {
        int total = 0;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.is(item)){
                total+=stack.getCount();
            }
        }
        return total;
    }

    /**
     * 扣除指定数量的物品。
     *
     * 数量足够：完整扣除，返回 true。
     * 数量不足：背包保持原样，返回 false。
     * amount 为 0：无需扣除，返回 true。
     * amount 为负数：抛出 IllegalArgumentException。
     */
    public static boolean consumeExact(
            SimpleContainer inventory,
            Item item,
            int amount
    ) {
        if (amount==0){
            return true;
        }
        if (amount<0){
            throw new IllegalArgumentException("扣除数量不能为负数");
        }

        if (amount<=count(inventory, item)){
            for (int i = 0; i < inventory.getContainerSize(); i++) {
                ItemStack stack = inventory.getItem(i);

                if (stack.is(item)) {
                    // 本格数量与剩余需求，取较小值。
                    int toRemove = Math.min(stack.getCount(), amount);

                    inventory.removeItem(i, toRemove);
                    amount -= toRemove;
                }

                if (amount == 0) {
                    break;
                }
            }
            return true;
        }
        return false;
    }

    /**
     * 复制背包及其中的物品堆。
     * 修改副本不会影响原背包。
     */
    public static SimpleContainer copy(SimpleContainer inventory) {
        SimpleContainer copied =
                new SimpleContainer(inventory.getContainerSize());

        for (int i = 0; i < inventory.getContainerSize(); i++) {
            copied.setItem(i, inventory.getItem(i).copy());
        }

        return copied;
    }
}