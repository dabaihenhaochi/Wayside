package io.github.dabaihenhaochi.wayside.gametest;

import io.github.dabaihenhaochi.wayside.Wayside;
import io.github.dabaihenhaochi.wayside.entity.NpcEntity;
import io.github.dabaihenhaochi.wayside.entity.action.ActionResult;
import io.github.dabaihenhaochi.wayside.entity.action.CraftRecipeAction;
import io.github.dabaihenhaochi.wayside.material.MaterialPlan;
import io.github.dabaihenhaochi.wayside.material.MaterialPlanner;
import io.github.dabaihenhaochi.wayside.registry.ModEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.Map;
import java.util.Set;

/** Verifies exact recipe execution without a material-supply task. */
@GameTestHolder(Wayside.MODID)
@PrefixGameTestTemplate(false)
public final class CraftRecipeActionGameTests {

    @GameTest(template = "empty", timeoutTicks = 260)
    public static void executesPlanksThenStairsFromOnePlan(GameTestHelper helper) {
        for (int x = 0; x < 12; x++) for (int z = 0; z < 12; z++) helper.setBlock(x, 0, z, Blocks.STONE);
        NpcEntity npc = helper.spawn(ModEntities.NPC.get(), new BlockPos(4, 1, 5));
        npc.getInventory().clearContent();
        npc.getTaskRunner().cancel();
        npc.getInventory().setItem(0, new ItemStack(Items.OAK_LOG, 3));
        helper.setBlock(3, 1, 5, Blocks.CRAFTING_TABLE);

        MaterialPlan plan = MaterialPlanner.plan((ServerLevel) helper.getLevel(),
                Map.of(Items.OAK_STAIRS, 8), npc.getInventory(), Set.of());
        helper.assertTrue(plan.isFeasible(), "Three oak logs should be enough for eight stairs");
        helper.assertTrue(!plan.craftSteps().isEmpty(), "Expected an executable recipe plan");

        int[] current = {0};
        CraftRecipeAction[] action = {null};
        helper.onEachTick(() -> {
            if (current[0] >= plan.craftSteps().size()) return;
            if (action[0] == null) {
                action[0] = new CraftRecipeAction(plan.craftSteps().get(current[0]));
            }
            ActionResult result = action[0].tick(npc);
            helper.assertTrue(!result.isFailed(),
                    "Executing planned recipe must not fail: " + result.failure());
            if (result.isSuccess()) {
                current[0]++;
                action[0] = null;
            }
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(current[0] == plan.craftSteps().size(), "Plan not yet finished");
            helper.assertTrue(count(npc.getInventory(), Items.OAK_STAIRS) == 8,
                    "Two stair crafts should create exactly eight stairs");
            helper.assertTrue(npc.getLogCount() == 0, "Exactly three logs must be consumed");
            helper.assertTrue(npc.getPlankCount() == 0, "All twelve planks should be consumed");
        });
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void blocksThreeByThreeRecipeWithoutTable(GameTestHelper helper) {
        for (int x = 0; x < 12; x++) for (int z = 0; z < 12; z++) helper.setBlock(x, 0, z, Blocks.STONE);
        NpcEntity npc = helper.spawn(ModEntities.NPC.get(), new BlockPos(4, 1, 5));
        npc.getInventory().clearContent();
        npc.getTaskRunner().cancel();
        npc.getInventory().setItem(0, new ItemStack(Items.OAK_PLANKS, 6));
        MaterialPlan plan = MaterialPlanner.plan((ServerLevel) helper.getLevel(),
                Map.of(Items.OAK_STAIRS, 4), npc.getInventory(), Set.of());
        helper.assertTrue(plan.isFeasible() && plan.craftSteps().size() == 1,
                "Expected one oak-stair crafting step");
        ActionResult result = new CraftRecipeAction(plan.craftSteps().get(0)).tick(npc);
        helper.assertTrue(result.failure() == ActionResult.Failure.MISSING_WORKSTATION,
                "3x3 recipes must require a nearby crafting table");
        helper.assertTrue(npc.getPlankCount() == 6, "No planks may be consumed");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void missingIngredientNeverMutatesInventory(GameTestHelper helper) {
        for (int x = 0; x < 12; x++) for (int z = 0; z < 12; z++) helper.setBlock(x, 0, z, Blocks.STONE);
        NpcEntity npc = helper.spawn(ModEntities.NPC.get(), new BlockPos(4, 1, 5));
        npc.getInventory().clearContent();
        npc.getTaskRunner().cancel();
        MaterialPlan plan = MaterialPlanner.plan((ServerLevel) helper.getLevel(),
                Map.of(Items.OAK_PLANKS, 4), npc.getInventory(), Set.of(Items.OAK_LOG));
        helper.assertTrue(plan.craftSteps().size() == 1, "Expected a plank recipe");
        CraftRecipeAction action = new CraftRecipeAction(plan.craftSteps().get(0));
        action.tick(npc);
        helper.runAtTickTime(30, () -> {
            ActionResult result = action.tick(npc);
            helper.assertTrue(result.failure() == ActionResult.Failure.MISSING_INGREDIENT,
                    "Missing logs must be reported before crafting");
            helper.assertTrue(npc.getLogCount() == 0 && npc.getPlankCount() == 0,
                    "Failed crafting may not change the inventory");
            helper.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void fullInventoryRejectsCraftAtomically(GameTestHelper helper) {
        for (int x = 0; x < 12; x++) for (int z = 0; z < 12; z++) helper.setBlock(x, 0, z, Blocks.STONE);
        NpcEntity npc = helper.spawn(ModEntities.NPC.get(), new BlockPos(4, 1, 5));
        npc.getInventory().clearContent();
        npc.getTaskRunner().cancel();
        for (int i = 0; i < npc.getInventory().getContainerSize(); i++) {
            npc.getInventory().setItem(i, new ItemStack(Items.STONE, 64));
        }
        npc.getInventory().setItem(0, new ItemStack(Items.OAK_LOG, 2));

        MaterialPlan plan = MaterialPlanner.plan((ServerLevel) helper.getLevel(),
                Map.of(Items.OAK_PLANKS, 4), npc.getInventory(), Set.of());
        helper.assertTrue(plan.isFeasible(), "Two stored logs can produce planks");
        CraftRecipeAction action = new CraftRecipeAction(plan.craftSteps().get(0));
        action.tick(npc);
        helper.runAtTickTime(30, () -> {
            ActionResult result = action.tick(npc);
            helper.assertTrue(result.failure() == ActionResult.Failure.NO_SPACE,
                    "No inventory slot can accept the output");
            helper.assertTrue(npc.getLogCount() == 2, "The input logs must be preserved");
            helper.assertTrue(npc.getPlankCount() == 0, "No output may be inserted");
            helper.succeed();
        });
    }

    private static int count(SimpleContainer inventory, net.minecraft.world.item.Item item) {
        int total = 0;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.is(item)) total += stack.getCount();
        }
        return total;
    }
}
