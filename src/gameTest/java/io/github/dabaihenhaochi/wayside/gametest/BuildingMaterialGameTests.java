package io.github.dabaihenhaochi.wayside.gametest;

import io.github.dabaihenhaochi.wayside.Wayside;
import io.github.dabaihenhaochi.wayside.building.BuildingMaterialCalculator;
import io.github.dabaihenhaochi.wayside.building.ConstructionPlanner;
import io.github.dabaihenhaochi.wayside.building.ConstructionOperation;
import io.github.dabaihenhaochi.wayside.entity.NpcEntity;
import io.github.dabaihenhaochi.wayside.entity.task.NpcTaskRunner;
import io.github.dabaihenhaochi.wayside.entity.task.resource.SupplyMaterialsTask;
import io.github.dabaihenhaochi.wayside.material.MaterialPlan;
import io.github.dabaihenhaochi.wayside.material.MaterialPlanner;
import io.github.dabaihenhaochi.wayside.registry.ModEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.Set;

@GameTestHolder(Wayside.MODID)
@PrefixGameTestTemplate(false)
public final class BuildingMaterialGameTests {

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void countsOnlyPendingPlaceAndReplace(GameTestHelper helper) {
        ConstructionPlanner.Plan construction = new ConstructionPlanner.Plan(List.of(
                new ConstructionOperation(new BlockPos(1, 1, 1),
                        Blocks.OAK_PLANKS.defaultBlockState(), ConstructionOperation.Action.PLACE),
                new ConstructionOperation(new BlockPos(2, 1, 1),
                        Blocks.OAK_PLANKS.defaultBlockState(), ConstructionOperation.Action.PLACE),
                new ConstructionOperation(new BlockPos(3, 1, 1),
                        Blocks.OAK_STAIRS.defaultBlockState(), ConstructionOperation.Action.REPLACE),
                new ConstructionOperation(new BlockPos(4, 1, 1),
                        Blocks.AIR.defaultBlockState(), ConstructionOperation.Action.REMOVE)
        ), 0, 0);
        var result = BuildingMaterialCalculator.calculate(construction);
        helper.assertTrue(result.canSupply(), "Ordinary one-block placements are supported");
        helper.assertTrue(result.requiredItems().size() == 2
                        && result.requiredItems().get(Items.OAK_PLANKS) == 2
                        && result.requiredItems().get(Items.OAK_STAIRS) == 1,
                "Only pending new blocks must be counted");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void refusesUnsupportedBlockStates(GameTestHelper helper) {
        ConstructionPlanner.Plan construction = new ConstructionPlanner.Plan(List.of(
                new ConstructionOperation(new BlockPos(1, 1, 1),
                        Blocks.OAK_DOOR.defaultBlockState(), ConstructionOperation.Action.PLACE),
                new ConstructionOperation(new BlockPos(2, 1, 1),
                        Blocks.RED_BED.defaultBlockState(),
                        ConstructionOperation.Action.PLACE),
                new ConstructionOperation(new BlockPos(3, 1, 1),
                        Blocks.OAK_STAIRS.defaultBlockState().setValue(
                                BlockStateProperties.WATERLOGGED, true),
                        ConstructionOperation.Action.PLACE),
                new ConstructionOperation(new BlockPos(4, 1, 1),
                        Blocks.OAK_PLANKS.defaultBlockState(), ConstructionOperation.Action.PLACE)
        ), 0, 0);
        var result = BuildingMaterialCalculator.calculate(construction);
        helper.assertTrue(!result.canSupply(), "Special placements require separate handling");
        helper.assertTrue(result.unsupported().size() == 3,
                "Door, bed and waterlogged stair must be reported");
        helper.assertTrue(result.requiredItems().size() == 1
                        && result.requiredItems().get(Items.OAK_PLANKS) == 1,
                "Valid blocks are still reported separately");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void preservesUnloadedAndBlockEntityWarnings(GameTestHelper helper) {
        var result = BuildingMaterialCalculator.calculate(
                new ConstructionPlanner.Plan(List.of(), 1, 2));
        helper.assertTrue(!result.canSupply(), "An incomplete blueprint must not proceed");
        helper.assertTrue(result.unavailablePositions() == 1
                        && result.uncheckedBlockEntities() == 2,
                "Construction planner warnings cannot be silently discarded");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void passesConstructionItemsToRecipePlanner(GameTestHelper helper) {
        ConstructionPlanner.Plan construction = new ConstructionPlanner.Plan(List.of(
                new ConstructionOperation(new BlockPos(1, 1, 1),
                        Blocks.OAK_STAIRS.defaultBlockState(), ConstructionOperation.Action.PLACE),
                new ConstructionOperation(new BlockPos(2, 1, 1),
                        Blocks.OAK_STAIRS.defaultBlockState(), ConstructionOperation.Action.PLACE),
                new ConstructionOperation(new BlockPos(3, 1, 1),
                        Blocks.OAK_PLANKS.defaultBlockState(), ConstructionOperation.Action.PLACE),
                new ConstructionOperation(new BlockPos(4, 1, 1),
                        Blocks.OAK_LOG.defaultBlockState(), ConstructionOperation.Action.PLACE)
        ), 0, 0);
        var counted = BuildingMaterialCalculator.calculate(construction);
        helper.assertTrue(counted.canSupply(), "Supported construction can request materials");
        MaterialPlan materials = MaterialPlanner.plan((ServerLevel) helper.getLevel(),
                counted.requiredItems(), new SimpleContainer(9), Set.of(Items.OAK_LOG));
        helper.assertTrue(materials.isFeasible(), "Oak materials can be derived from oak logs");
        helper.assertTrue(materials.requiredSupplies().getOrDefault(Items.OAK_LOG, 0) == 3,
                "Two stairs, a plank and a placed log require three logs");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 280)
    public static void suppliesCalculatedBuildingMaterials(GameTestHelper helper) {
        for (int x = 0; x < 10; x++) {
            for (int z = 0; z < 10; z++) {
                helper.setBlock(x, 0, z, Blocks.STONE);
            }
        }
        helper.setBlock(3, 1, 5, Blocks.CRAFTING_TABLE);
        NpcEntity npc = helper.spawn(ModEntities.NPC.get(), new BlockPos(4, 1, 5));
        npc.getInventory().clearContent();
        npc.getInventory().setItem(0, new ItemStack(Items.OAK_LOG, 3));

        var construction = new ConstructionPlanner.Plan(List.of(
                new ConstructionOperation(new BlockPos(1, 1, 1),
                        Blocks.OAK_STAIRS.defaultBlockState(), ConstructionOperation.Action.PLACE),
                new ConstructionOperation(new BlockPos(2, 1, 1),
                        Blocks.OAK_STAIRS.defaultBlockState(), ConstructionOperation.Action.PLACE),
                new ConstructionOperation(new BlockPos(3, 1, 1),
                        Blocks.OAK_STAIRS.defaultBlockState(), ConstructionOperation.Action.PLACE),
                new ConstructionOperation(new BlockPos(4, 1, 1),
                        Blocks.OAK_STAIRS.defaultBlockState(), ConstructionOperation.Action.PLACE)
        ), 0, 0);
        var materials = BuildingMaterialCalculator.calculate(construction);
        helper.assertTrue(materials.canSupply(), "Building should have countable materials");
        npc.getTaskRunner().setTask(new SupplyMaterialsTask(materials.requiredItems()));

        helper.succeedWhen(() -> {
            helper.assertTrue(npc.getTaskRunner().state() == NpcTaskRunner.State.COMPLETED,
                    "Material supply should complete from a construction requirement");
            int stairs = 0;
            for (int slot = 0; slot < npc.getInventory().getContainerSize(); slot++) {
                ItemStack stack = npc.getInventory().getItem(slot);
                if (stack.is(Items.OAK_STAIRS)) stairs += stack.getCount();
            }
            helper.assertTrue(stairs >= 4, "NPC should have at least four stairs to build with");
        });
    }
    @GameTest(template = "empty", timeoutTicks = 100)
    public static void countsGenericBlocksAndCompositeCosts(GameTestHelper helper) {
        var result = BuildingMaterialCalculator.calculate(new ConstructionPlanner.Plan(List.of(
                new ConstructionOperation(new BlockPos(1, 1, 1),
                        Blocks.GLASS.defaultBlockState(), ConstructionOperation.Action.PLACE),
                new ConstructionOperation(new BlockPos(2, 1, 1),
                        Blocks.STONE_BRICKS.defaultBlockState(), ConstructionOperation.Action.PLACE),
                new ConstructionOperation(new BlockPos(3, 1, 1),
                        Blocks.OAK_FENCE.defaultBlockState(), ConstructionOperation.Action.PLACE),
                new ConstructionOperation(new BlockPos(4, 1, 1),
                        Blocks.OAK_STAIRS.defaultBlockState(), ConstructionOperation.Action.PLACE),
                new ConstructionOperation(new BlockPos(5, 1, 1),
                        Blocks.OAK_SLAB.defaultBlockState().setValue(
                                BlockStateProperties.SLAB_TYPE, SlabType.DOUBLE),
                        ConstructionOperation.Action.PLACE),
                new ConstructionOperation(new BlockPos(6, 1, 1),
                        Blocks.SNOW.defaultBlockState().setValue(BlockStateProperties.LAYERS, 3),
                        ConstructionOperation.Action.PLACE)
        ), 0, 0));
        helper.assertTrue(result.canSupply(), "Ordinary single-position blocks should be countable");
        helper.assertTrue(result.requiredItems().getOrDefault(Items.GLASS, 0) == 1,
                "Glass should not require an explicit whitelist entry");
        helper.assertTrue(result.requiredItems().getOrDefault(Items.STONE_BRICKS, 0) == 1
                        && result.requiredItems().getOrDefault(Items.OAK_FENCE, 0) == 1
                        && result.requiredItems().getOrDefault(Items.OAK_STAIRS, 0) == 1,
                "Brick, fence and stair costs should be included");
        helper.assertTrue(result.requiredItems().getOrDefault(Items.OAK_SLAB, 0) == 2,
                "A double slab costs two slab items");
        helper.assertTrue(result.requiredItems().getOrDefault(Items.SNOW, 0) == 3,
                "Three snow layers cost three snow items");
        helper.succeed();
    }
}

