package io.github.dabaihenhaochi.wayside.entity.inventory;

import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;

/** NPC 出生时的默认背包。只配置初始物资，不读取或创建任何任务。 */
public final class NpcInitialSupplies {
    private NpcInitialSupplies() {}

    public static SimpleContainer createInventory(int slots) {
        SimpleContainer inventory = new SimpleContainer(slots);
        // 修改这里即可调整初始物品和数量；每次创建独立物品堆。
        List<ItemStack> supplies = List.of(
                new ItemStack(Items.OAK_LOG, 16),
                new ItemStack(Items.OAK_PLANKS, 64),
                new ItemStack(Items.OAK_STAIRS, 64),
                new ItemStack(Items.DIRT, 64)
        );
        for (ItemStack stack : supplies) {
            if (!inventory.addItem(stack).isEmpty()) {
                throw new IllegalStateException("NPC 初始物资超出背包容量，请调整物资或格数");
            }
        }
        return inventory;
    }
}
