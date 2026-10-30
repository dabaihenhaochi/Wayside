package io.github.dabaihenhaochi.wayside.entity.inventory;

import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.tags.TagKey;
import java.util.function.Predicate;

//背包操作工具类。
public final class InventoryOps {

    // 工具类不需要创建对象。
    private InventoryOps() {
    }

    //统计背包中指定物品的总数量。不修改背包。
    public static int count(SimpleContainer inventory, Item item) {
        return count(inventory, stack -> stack.is(item));
    }

    // 标签计数不绑定某个业务物品，例如原木、木板或其他物品组。
    public static int count(SimpleContainer inventory, TagKey<Item> tag) {
        return count(inventory, stack -> stack.is(tag));
    }

    public static int count(SimpleContainer inventory, Predicate<ItemStack> matches) {
        int total = 0;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty() && matches.test(stack)){
                total+=stack.getCount();
            }
        }
        return total;
    }

    /** 原子地用某格原料换取产物；空间不足时原背包不变。 */
    public static boolean exchange(SimpleContainer inventory, int slot, int amount, ItemStack output) {
        if (amount <= 0) throw new IllegalArgumentException("交换数量必须为正数");
        if (slot < 0 || slot >= inventory.getContainerSize()) throw new IllegalArgumentException("无效背包格");
        if (inventory.getItem(slot).getCount() < amount) return false;
        SimpleContainer simulated = copy(inventory);
        simulated.removeItem(slot, amount);
        if (!simulated.addItem(output.copy()).isEmpty()) return false;
        replaceContents(inventory, simulated);
        return true;
    }

    /** 提交已检查的背包快照，复制物品堆以免共享可变对象。 */
    public static void replaceContents(SimpleContainer inventory, SimpleContainer contents) {
        if (inventory.getContainerSize() != contents.getContainerSize()) throw new IllegalArgumentException("背包格数不一致");
        SimpleContainer snapshot = copy(contents);
        for (int i = 0; i < inventory.getContainerSize(); i++) inventory.setItem(i, snapshot.getItem(i).copy());
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
