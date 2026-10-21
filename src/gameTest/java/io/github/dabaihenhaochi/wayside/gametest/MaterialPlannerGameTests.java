package io.github.dabaihenhaochi.wayside.gametest;

import io.github.dabaihenhaochi.wayside.Wayside;
import io.github.dabaihenhaochi.wayside.material.MaterialPlan;
import io.github.dabaihenhaochi.wayside.material.MaterialPlanner;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

@GameTestHolder(Wayside.MODID)
@PrefixGameTestTemplate(false)
public final class MaterialPlannerGameTests {
    @GameTest(template = "empty", timeoutTicks = 100)
    public static void alreadyStockedNeedsNoCrafting(GameTestHelper helper) {
        SimpleContainer inventory = new SimpleContainer(9);
        inventory.setItem(0, new ItemStack(Items.OAK_PLANKS, 16));
        MaterialPlan plan = MaterialPlanner.plan((ServerLevel) helper.getLevel(),
                Map.of(Items.OAK_PLANKS, 12), inventory, Set.of(Items.OAK_LOG));
        helper.assertTrue(plan.isFeasible(), "Stored planks should fulfill the request");
        helper.assertTrue(plan.craftSteps().isEmpty(), "Stored goods must not be recrafted");
        helper.assertTrue(plan.requiredSupplies().isEmpty(), "No more logs are necessary");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void stairsAndPlanksShareLogs(GameTestHelper helper) {
        Map<Item, Integer> requested = new LinkedHashMap<>();
        requested.put(Items.OAK_STAIRS, 8);
        requested.put(Items.OAK_PLANKS, 24);
        requested.put(Items.OAK_LOG, 8);
        SimpleContainer inventory = new SimpleContainer(9);
        MaterialPlan plan = MaterialPlanner.plan((ServerLevel) helper.getLevel(),
                requested, inventory, Set.of(Items.OAK_LOG));
        helper.assertTrue(plan.isFeasible(), "Stairs must have an ordinary crafting path");
        helper.assertTrue(plan.requiredSupplies().getOrDefault(Items.OAK_LOG, 0) == 17,
                "Eight stairs, 24 planks, eight logs should need exactly 17 oak logs");
        helper.assertTrue(!plan.craftSteps().isEmpty(), "Expected crafting operations");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void missingBaseResourceIsReported(GameTestHelper helper) {
        MaterialPlan plan = MaterialPlanner.plan((ServerLevel) helper.getLevel(),
                Map.of(Items.OAK_STAIRS, 4), new SimpleContainer(9), Set.of());
        helper.assertTrue(!plan.isFeasible(), "No wood source should mean an unmet requirement");
        helper.assertTrue(!plan.unavailable().isEmpty(), "The unavailable materials must be reported");
        helper.succeed();
    }
}
