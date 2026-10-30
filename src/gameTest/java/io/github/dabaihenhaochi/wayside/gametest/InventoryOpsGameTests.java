package io.github.dabaihenhaochi.wayside.gametest;

import io.github.dabaihenhaochi.wayside.Wayside;
import io.github.dabaihenhaochi.wayside.entity.inventory.InventoryOps;
import io.github.dabaihenhaochi.wayside.entity.task.build.PrepareBuildingSiteTask;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.tags.ItemTags;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(Wayside.MODID)
@PrefixGameTestTemplate(false)
public final class InventoryOpsGameTests {
    @GameTest(template = "empty", timeoutTicks = 100)
    public static void tagCountsIncludeVariantsAndExcludeOtherItems(GameTestHelper helper) {
        var inventory = new SimpleContainer(5);
        inventory.setItem(0, new ItemStack(Items.OAK_LOG, 3));
        inventory.setItem(1, new ItemStack(Items.BIRCH_LOG, 5));
        inventory.setItem(2, new ItemStack(Items.OAK_PLANKS, 7));
        inventory.setItem(3, new ItemStack(Items.DIRT, 11));
        helper.assertTrue(InventoryOps.count(inventory, ItemTags.LOGS) == 8
                        && InventoryOps.count(inventory, ItemTags.PLANKS) == 7
                        && InventoryOps.count(inventory, Items.OAK_LOG) == 3,
                "Tag counting must include variants without changing exact-item counting");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void failedExchangePreservesInputAndOtherSlots(GameTestHelper helper) {
        var inventory = new SimpleContainer(2);
        inventory.setItem(0, new ItemStack(Items.OAK_LOG, 2));
        inventory.setItem(1, new ItemStack(Items.STONE, 64));
        helper.assertTrue(!InventoryOps.exchange(inventory, 0, 1, new ItemStack(Items.OAK_PLANKS, 4)),
                "An exchange must reject output that cannot fit");
        helper.assertTrue(inventory.countItem(Items.OAK_LOG) == 2 && inventory.countItem(Items.STONE) == 64,
                "Failed exchange cannot partially consume input");
        helper.assertTrue(InventoryOps.exchange(inventory, 0, 2, new ItemStack(Items.OAK_PLANKS, 8)),
                "Consuming the whole input slot should free space for the output");
        helper.assertTrue(inventory.countItem(Items.OAK_LOG) == 0 && inventory.countItem(Items.OAK_PLANKS) == 8,
                "Successful exchange must commit exact quantities");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void snapshotCommitDoesNotShareMutableStacks(GameTestHelper helper) {
        var inventory = new SimpleContainer(2);
        var snapshot = new SimpleContainer(2);
        snapshot.setItem(0, new ItemStack(Items.DIRT, 5));
        InventoryOps.replaceContents(inventory, snapshot);
        snapshot.getItem(0).shrink(3);
        helper.assertTrue(inventory.countItem(Items.DIRT) == 5, "Committed stacks must be independent of the snapshot");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void siteRecoveryStateSurvivesLoadAndOldDataDefaultsToZero(GameTestHelper helper) {
        var task = new PrepareBuildingSiteTask();
        var saved = new net.minecraft.nbt.CompoundTag();
        var execution = new net.minecraft.nbt.CompoundTag();
        execution.putInt("ConsecutiveRise", 6);
        saved.put("Execution", execution);
        task.load(saved);
        helper.assertTrue(task.save().getCompound("Execution").getInt("ConsecutiveRise") == 6,
                "Site tasks must preserve recovery progress independently of the active action");
        task.load(new net.minecraft.nbt.CompoundTag());
        helper.assertTrue(task.save().getCompound("Execution").getInt("ConsecutiveRise") == 0,
                "Old saves without execution data must remain compatible");
        helper.succeed();
    }
}
