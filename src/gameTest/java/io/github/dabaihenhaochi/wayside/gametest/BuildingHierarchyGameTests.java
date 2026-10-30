package io.github.dabaihenhaochi.wayside.gametest;

import io.github.dabaihenhaochi.wayside.Wayside;
import io.github.dabaihenhaochi.wayside.building.BuildingBlueprint;
import io.github.dabaihenhaochi.wayside.building.BuildingInstance;
import io.github.dabaihenhaochi.wayside.building.BuildingManager;
import io.github.dabaihenhaochi.wayside.building.ConstructionPlanner;
import io.github.dabaihenhaochi.wayside.entity.NpcEntity;
import io.github.dabaihenhaochi.wayside.entity.action.ActionResult;
import io.github.dabaihenhaochi.wayside.entity.task.NpcTask;
import io.github.dabaihenhaochi.wayside.entity.task.NpcTaskRunner;
import io.github.dabaihenhaochi.wayside.entity.task.TaskSequence;
import io.github.dabaihenhaochi.wayside.entity.task.build.BuildBuildingTask;
import io.github.dabaihenhaochi.wayside.registry.ModEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.UUID;

@GameTestHolder(Wayside.MODID)
@PrefixGameTestTemplate(false)
public final class BuildingHierarchyGameTests {
    private static NpcEntity npc(GameTestHelper helper) {
        for (int x = 0; x < 12; x++) for (int z = 0; z < 12; z++) helper.setBlock(x, 0, z, Blocks.STONE);
        NpcEntity npc = helper.spawn(ModEntities.NPC.get(), new BlockPos(4, 1, 5));
        npc.getTaskRunner().cancel();
        npc.getInventory().clearContent();
        return npc;
    }

    private static ResourceLocation blueprint(GameTestHelper helper, String name, Block material, int width) {
        BlockPos prototype = new BlockPos(1, 8, 1);
        for (int x = 0; x < width; x++) helper.setBlock(prototype.offset(x, 0, 0), material);
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath("wayside", "hierarchy_" + name);
        helper.getLevel().getStructureManager().getOrCreate(id).fillFromWorld(helper.getLevel(),
                helper.absolutePos(prototype), new Vec3i(width, 1, 1), false, Blocks.STRUCTURE_VOID);
        return id;
    }

    private static TaskSequence parent(BuildBuildingTask house, int[] finished) {
        return new TaskSequence("test_survival", List.of(new TaskSequence("test_home_goal", List.of(house)), new NpcTask() {
            @Override public String typeId() { return "test_next_goal"; }
            @Override public String stageName() { return "NEXT"; }
            @Override public void pause(NpcEntity npc) {}
            @Override public ActionResult tick(NpcEntity npc) { finished[0]++; return ActionResult.success(); }
        }));
    }

    @GameTest(template = "empty", timeoutTicks = 600)
    public static void nestedBuildingSuppliesMaterialsAndReloadsBeforeTerrain(GameTestHelper helper) {
        NpcEntity npc = npc(helper);
        ResourceLocation blueprint = blueprint(helper, "plank_supply", Blocks.OAK_PLANKS, 2);
        npc.getInventory().setItem(0, new ItemStack(Items.OAK_LOG, 1));
        int[] finished = {0};
        BuildBuildingTask[] house = {new BuildBuildingTask(blueprint, Rotation.NONE, 6, 0, 2)};
        TaskSequence[] root = {parent(house[0], finished)};
        npc.getTaskRunner().setTask(root[0]);
        boolean[] reloaded = {false};
        UUID[] selected = {null};
        helper.onEachTick(() -> {
            helper.assertTrue(npc.getTaskRunner().state() != NpcTaskRunner.State.FAILED,
                    "Nested build failed: " + npc.getTaskRunner().description());
            if (!reloaded[0] && house[0].currentStepName().equals("TERRAIN")) {
                helper.assertTrue(npc.getInventory().countItem(Items.OAK_LOG) == 0 && npc.getInventory().countItem(Items.OAK_PLANKS) == 4,
                        "Materials must be produced by the nested supply task before terrain starts");
                selected[0] = house[0].save().getCompound("Project").getCompound("Building").getUUID("Building");
                CompoundTag saved = root[0].save();
                house[0] = new BuildBuildingTask(blueprint, Rotation.NONE, 6, 0, 2);
                root[0] = parent(house[0], finished);
                root[0].load(saved);
                npc.getTaskRunner().setTask(root[0]);
                reloaded[0] = true;
            }
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(reloaded[0] && npc.getTaskRunner().state() == NpcTaskRunner.State.COMPLETED && finished[0] == 1,
                    "Only a complete nested building may advance the outer goal");
            var building = BuildingInstance.load(house[0].save().getCompound("Project").getCompound("Building"));
            helper.assertTrue(building.id().equals(selected[0]), "Reload must reuse the selected site");
            helper.assertTrue(ConstructionPlanner.createPlan(helper.getLevel(), BuildingBlueprint.load(helper.getLevel(), blueprint).orElseThrow(),
                    building, ConstructionPlanner.AirPolicy.CLEAR).isComplete(), "Completed world must match the requested blueprint");
            helper.assertTrue(npc.getInventory().countItem(Items.OAK_LOG) == 0 && npc.getInventory().countItem(Items.OAK_PLANKS) == 2,
                    "One log produces four planks and two placed blocks consume exactly two");
        });
    }

    @GameTest(template = "empty", timeoutTicks = 350)
    public static void failedSupplyReloadAndRetryKeepTheSameReservedSite(GameTestHelper helper) {
        NpcEntity npc = npc(helper);
        ResourceLocation blueprint = blueprint(helper, "stone_retry", Blocks.STONE, 1);
        npc.getTaskRunner().setTask(new BuildBuildingTask(blueprint, Rotation.NONE, 6, 0, 2));
        UUID[] selected = {null};
        BlockPos[] origin = {null};
        helper.onEachTick(() -> {
            if (selected[0] == null && npc.getTaskRunner().state() == NpcTaskRunner.State.FAILED) {
                helper.assertTrue(npc.getTaskRunner().lastResult().failure() == ActionResult.Failure.MISSING_INGREDIENT,
                        "Unavailable stone supply must stop before modifying the site");
                var building = BuildingInstance.load(npc.getTaskRunner().save().getCompound("Data").getCompound("Project").getCompound("Building"));
                selected[0] = building.id();
                origin[0] = building.origin();
                helper.assertTrue(helper.getLevel().getBlockState(origin[0]).isAir(), "Failed supply must leave terrain untouched");
                CompoundTag saved = new CompoundTag();
                saved.put("WaysideTask", npc.getTaskRunner().save());
                npc.getTaskRunner().load(saved);
                helper.assertTrue(npc.getTaskRunner().state() == NpcTaskRunner.State.FAILED, "Reload must preserve failure until retry");
                npc.getInventory().setItem(0, new ItemStack(Items.STONE, 1));
                npc.getTaskRunner().retry();
            }
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(selected[0] != null && npc.getTaskRunner().state() == NpcTaskRunner.State.COMPLETED,
                    "Supplying the missing material then retrying must finish");
            var building = BuildingInstance.load(npc.getTaskRunner().save().getCompound("Data").getCompound("Project").getCompound("Building"));
            helper.assertTrue(building.id().equals(selected[0]) && helper.getLevel().getBlockState(origin[0]).is(Blocks.STONE),
                    "Retry must build at the original site");
            helper.assertTrue(new BuildingManager(helper.getLevel()).getBuildings().stream().filter(value -> value.id().equals(selected[0])).count() == 1
                            && npc.getInventory().countItem(Items.STONE) == 0,
                    "Retry must not duplicate reservations or material charges");
        });
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void missingBlueprintFailsDuringInitializationWithoutSelectingSite(GameTestHelper helper) {
        NpcEntity npc = npc(helper);
        var task = new BuildBuildingTask(ResourceLocation.fromNamespaceAndPath("wayside", "missing_simplification_blueprint"), Rotation.NONE, 6, 0, 2);
        helper.assertTrue(task.tick(npc).failure() == ActionResult.Failure.INVALID_TARGET
                        && !task.save().getCompound("Project").contains("Building") && npc.getInventory().isEmpty(),
                "Initialization must reject a missing blueprint before site selection or material operations");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void previousBlueprintStepIndexResumesConstructionWithoutPreparingAgain(GameTestHelper helper) {
        NpcEntity npc = npc(helper);
        ResourceLocation blueprint = blueprint(helper, "previous_step_index", Blocks.OAK_PLANKS, 2);
        npc.getInventory().setItem(0, new ItemStack(Items.OAK_PLANKS, 2));
        BuildBuildingTask task = new BuildBuildingTask(blueprint, Rotation.NONE, 6, 0, 2);
        npc.getTaskRunner().setTask(task);
        boolean[] restored = {false};
        BuildingInstance[] building = {null};
        helper.onEachTick(() -> {
            helper.assertTrue(npc.getTaskRunner().state() != NpcTaskRunner.State.FAILED,
                    "Consolidating initialization must not repeat terrain work: " + npc.getTaskRunner().description());
            if (!restored[0] && task.currentStepName().equals("CONSTRUCTION")) {
                CompoundTag saved = task.save();
                building[0] = BuildingInstance.load(saved.getCompound("Project").getCompound("Building"));
                // 模拟上一轮版本：蓝图占一个独立步骤，且已经完成一个房屋方块。
                helper.getLevel().setBlockAndUpdate(building[0].origin(), Blocks.OAK_PLANKS.defaultBlockState());
                npc.getInventory().removeItem(0, 1);
                saved.putInt("Current", saved.getInt("Current") + 1);
                saved.getCompound("Steps").put("BLUEPRINT", new CompoundTag());
                BuildBuildingTask loaded = new BuildBuildingTask();
                loaded.load(saved);
                npc.getTaskRunner().setTask(loaded);
                restored[0] = true;
            }
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(restored[0] && npc.getTaskRunner().state() == NpcTaskRunner.State.COMPLETED,
                    "The saved construction step must finish after consolidation");
            helper.assertTrue(ConstructionPlanner.createPlan(helper.getLevel(), BuildingBlueprint.load(helper.getLevel(), blueprint).orElseThrow(),
                            building[0], ConstructionPlanner.AirPolicy.CLEAR).isComplete()
                            && npc.getInventory().countItem(Items.OAK_PLANKS) == 0,
                    "Completed blocks must remain, and remaining blocks must consume only their own materials");
        });
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void buildingProjectRejectsAnotherDimensionBeforeAnyChildRuns(GameTestHelper helper) {
        NpcEntity npc = npc(helper);
        var task = new BuildBuildingTask(blueprint(helper, "dimension", Blocks.OAK_PLANKS, 1), Rotation.NONE, 6, 0, 2);
        CompoundTag saved = task.save();
        saved.getCompound("Project").putString("Dimension", "minecraft:the_nether");
        task.load(saved);
        helper.assertTrue(task.tick(npc).failure() == ActionResult.Failure.INVALID_TARGET
                        && npc.getInventory().isEmpty() && !task.save().getCompound("Project").contains("Building"),
                "A dimension mismatch must not select or build at the same coordinates");
        helper.succeed();
    }
}
