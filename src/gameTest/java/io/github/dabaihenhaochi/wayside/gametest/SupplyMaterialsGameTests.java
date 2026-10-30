package io.github.dabaihenhaochi.wayside.gametest;

import io.github.dabaihenhaochi.wayside.Wayside;
import net.minecraft.tags.ItemTags;
import io.github.dabaihenhaochi.wayside.entity.inventory.InventoryOps;
import io.github.dabaihenhaochi.wayside.entity.NpcEntity;
import io.github.dabaihenhaochi.wayside.entity.action.ActionResult;
import io.github.dabaihenhaochi.wayside.entity.task.NpcTaskRunner;
import io.github.dabaihenhaochi.wayside.entity.task.resource.SupplyMaterialsTask;
import io.github.dabaihenhaochi.wayside.registry.ModEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.Map;

/** Full TaskRunner -> material plan -> gather/craft integration tests. */
@GameTestHolder(Wayside.MODID)
@PrefixGameTestTemplate(false)
public final class SupplyMaterialsGameTests {
    @GameTest(template = "empty", timeoutTicks = 100)
    public static void stockedGoalRequiresNoProduction(GameTestHelper helper) {
        prepareFloor(helper);
        NpcEntity npc = helper.spawn(ModEntities.NPC.get(), new BlockPos(4, 1, 5));
        npc.getInventory().clearContent();
        npc.getInventory().setItem(0, new ItemStack(Items.OAK_STAIRS, 8));
        npc.getInventory().setItem(1, new ItemStack(Items.OAK_LOG, 3));
        npc.getTaskRunner().setTask(new SupplyMaterialsTask(Map.of(Items.OAK_STAIRS, 8)));

        helper.runAtTickTime(30, () -> {
            helper.assertTrue(npc.getTaskRunner().state() == NpcTaskRunner.State.COMPLETED,
                    "Existing stairs must complete the request");
            helper.assertTrue(count(npc, Items.OAK_STAIRS) == 8 && InventoryOps.count(npc.getInventory(), ItemTags.LOGS) == 3,
                    "A fulfilled requirement must not consume supplies");
            helper.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 280)
    public static void craftsStairsFromStoredLogs(GameTestHelper helper) {
        prepareFloor(helper);
        helper.setBlock(3, 1, 5, Blocks.CRAFTING_TABLE);
        NpcEntity npc = helper.spawn(ModEntities.NPC.get(), new BlockPos(4, 1, 5));
        npc.getInventory().clearContent();
        npc.getInventory().setItem(0, new ItemStack(Items.OAK_LOG, 3));
        npc.getTaskRunner().setTask(new SupplyMaterialsTask(Map.of(Items.OAK_STAIRS, 8)));

        helper.succeedWhen(() -> {
            helper.assertTrue(npc.getTaskRunner().state() == NpcTaskRunner.State.COMPLETED,
                    "The material task must finish its craft steps");
            helper.assertTrue(count(npc, Items.OAK_STAIRS) == 8
                            && InventoryOps.count(npc.getInventory(), ItemTags.LOGS) == 0 && InventoryOps.count(npc.getInventory(), ItemTags.PLANKS) == 0,
                    "Three stored logs should become exactly eight stairs");
        });
    }

    @GameTest(template = "empty", timeoutTicks = 950)
    public static void gathersMissingLogsThenCrafts(GameTestHelper helper) {
        prepareFloor(helper);
        helper.setBlock(3, 1, 5, Blocks.CRAFTING_TABLE);
        BlockPos first = new BlockPos(5, 1, 5);
        BlockPos second = new BlockPos(7, 1, 5);
        helper.setBlock(first, Blocks.OAK_LOG);
        helper.setBlock(second, Blocks.OAK_LOG);

        NpcEntity npc = helper.spawn(ModEntities.NPC.get(), new BlockPos(4, 1, 5));
        npc.getInventory().clearContent();
        npc.getInventory().setItem(0, new ItemStack(Items.OAK_LOG, 1));
        npc.getTaskRunner().setTask(new SupplyMaterialsTask(Map.of(Items.OAK_STAIRS, 8)));

        helper.succeedWhen(() -> {
            helper.assertTrue(npc.getTaskRunner().state() == NpcTaskRunner.State.COMPLETED,
                    "NPC must collect exactly the missing logs and craft stairs");
            helper.assertTrue(count(npc, Items.OAK_STAIRS) == 8, "Must produce eight stairs");
            helper.assertBlockPresent(Blocks.AIR, first);
            helper.assertBlockPresent(Blocks.AIR, second);
        });
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void missingWorkstationFailsWithoutSpendingMaterials(GameTestHelper helper) {
        prepareFloor(helper);
        NpcEntity npc = helper.spawn(ModEntities.NPC.get(), new BlockPos(4, 1, 5));
        npc.getInventory().clearContent();
        npc.getInventory().setItem(0, new ItemStack(Items.OAK_PLANKS, 6));
        NpcTaskRunner runner = npc.getTaskRunner();
        runner.setTask(new SupplyMaterialsTask(Map.of(Items.OAK_STAIRS, 4)));
        runner.tick();

        helper.assertTrue(runner.state() == NpcTaskRunner.State.FAILED,
                "A three-by-three recipe without a crafting table must fail");
        helper.assertTrue(runner.lastResult().failure() == ActionResult.Failure.MISSING_WORKSTATION,
                "A missing crafting table should be reported");
        helper.assertTrue(InventoryOps.count(npc.getInventory(), ItemTags.PLANKS) == 6, "Failed crafting cannot consume planks");

        helper.setBlock(3, 1, 5, Blocks.CRAFTING_TABLE);
        runner.retry();
        helper.succeedWhen(() -> {
            helper.assertTrue(runner.state() == NpcTaskRunner.State.COMPLETED,
                    "Explicit retry should work after installing a crafting table");
            helper.assertTrue(count(npc, Items.OAK_STAIRS) == 4,
                    "Retry must craft the requested stairs");
        });
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void unavailableResourceFailsInsteadOfInventingIt(GameTestHelper helper) {
        prepareFloor(helper);
        NpcEntity npc = helper.spawn(ModEntities.NPC.get(), new BlockPos(4, 1, 5));
        npc.getInventory().clearContent();
        NpcTaskRunner runner = npc.getTaskRunner();
        runner.setTask(new SupplyMaterialsTask(Map.of(Items.STONE, 3)));
        runner.tick();
        helper.assertTrue(runner.state() == NpcTaskRunner.State.FAILED,
                "Stone has no enabled crafting or supply path in this phase");
        helper.assertTrue(runner.lastResult().failure() == ActionResult.Failure.MISSING_INGREDIENT,
                "An unavailable resource must be reported as missing");
        helper.assertTrue(count(npc, Items.STONE) == 0,
                "No stone may be created from nothing");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 300)
    public static void reloadReplansFromActualInventory(GameTestHelper helper) {
        prepareFloor(helper);
        helper.setBlock(3, 1, 5, Blocks.CRAFTING_TABLE);
        NpcEntity npc = helper.spawn(ModEntities.NPC.get(), new BlockPos(4, 1, 5));
        npc.getInventory().clearContent();
        npc.getInventory().setItem(0, new ItemStack(Items.OAK_LOG, 3));
        NpcTaskRunner runner = npc.getTaskRunner();
        runner.setTask(new SupplyMaterialsTask(Map.of(Items.OAK_STAIRS, 8)));

        helper.runAtTickTime(45, () -> {
            CompoundTag tag = new CompoundTag();
            tag.put("WaysideTask", runner.save());
            runner.load(tag);
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(runner.state() == NpcTaskRunner.State.COMPLETED,
                    "Restored task must finish from the actual inventory");
            helper.assertTrue(count(npc, Items.OAK_STAIRS) == 8
                            && InventoryOps.count(npc.getInventory(), ItemTags.LOGS) == 0 && InventoryOps.count(npc.getInventory(), ItemTags.PLANKS) == 0,
                    "Reload must neither lose nor duplicate ingredients");
        });
    }

    private static int count(NpcEntity npc, net.minecraft.world.item.Item item) {
        int total = 0;
        for (int i = 0; i < npc.getInventory().getContainerSize(); i++) {
            ItemStack stack = npc.getInventory().getItem(i);
            if (stack.is(item)) total += stack.getCount();
        }
        return total;
    }

    private static void prepareFloor(GameTestHelper helper) {
        for (int x = 0; x < 12; x++) {
            for (int z = 0; z < 12; z++) {
                helper.setBlock(x, 0, z, Blocks.STONE);
            }
        }
    }
}
