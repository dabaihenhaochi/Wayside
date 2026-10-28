package io.github.dabaihenhaochi.wayside.gametest;

import io.github.dabaihenhaochi.wayside.entity.task.build.BuildHutTask;
import io.github.dabaihenhaochi.wayside.entity.task.build.BuildStructureTask;

import io.github.dabaihenhaochi.wayside.Wayside;
import io.github.dabaihenhaochi.wayside.building.*;
import io.github.dabaihenhaochi.wayside.entity.NpcEntity;
import io.github.dabaihenhaochi.wayside.entity.action.*;
import io.github.dabaihenhaochi.wayside.entity.ai.NpcBlockInteraction;
import io.github.dabaihenhaochi.wayside.entity.task.*;
import io.github.dabaihenhaochi.wayside.registry.ModEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;
import java.util.function.Consumer;

@GameTestHolder(Wayside.MODID)
@PrefixGameTestTemplate(false)
public final class ConstructionGameTests {
    private static final BlockPos ORIGIN = new BlockPos(7, 1, 5);
    private static final BlockPos PROTOTYPE = new BlockPos(1, 7, 1);

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void sharedSupportRuleRejectsFloatingSandAndAllowsSupportedSand(GameTestHelper helper) {
        NpcEntity npc = prepare(helper);
        BlockPos relative = new BlockPos(3, 1, 5);
        BlockPos target = helper.absolutePos(relative);
        helper.setBlock(relative.below(), Blocks.AIR);
        npc.getInventory().setItem(0, new ItemStack(Items.SAND, 1));
        var state = Blocks.SAND.defaultBlockState();
        helper.assertTrue(!NpcBlockInteraction.hasPlacementSupport(helper.getLevel(), target, state),
                "Planning must reject a falling block with no support");
        var action = new PlaceBlockAction(target, state);
        helper.assertTrue(action.tick(npc).failure() == ActionResult.Failure.NO_SPACE
                        && npc.getInventory().countItem(Items.SAND) == 1,
                "Execution must reject unsupported sand without spending material");
        helper.setBlock(relative.below(), Blocks.STONE);
        helper.assertTrue(NpcBlockInteraction.hasPlacementSupport(helper.getLevel(), target, state),
                "Installing support must make the same target available");
        helper.assertTrue(action.tick(npc).isSuccess() && npc.getInventory().countItem(Items.SAND) == 0,
                "Supported sand must place and consume exactly one item");
        helper.assertBlockPresent(Blocks.SAND, relative);
        helper.succeed();
    }

    private static NpcEntity prepare(GameTestHelper helper) {
        for (int x = 0; x < 15; x++) for (int z = 0; z < 13; z++) helper.setBlock(x, 0, z, Blocks.STONE);
        NpcEntity npc = helper.spawn(ModEntities.NPC.get(), new BlockPos(2, 1, 5));
        npc.getTaskRunner().cancel();
        npc.getInventory().clearContent(); // 此处单独验证施工，自行设置测试材料。
        return npc;
    }

    private static BuildingInstance blueprint(GameTestHelper helper, String name, boolean house) {
        Vec3i size = house ? new Vec3i(3, 4, 3) : new Vec3i(1, 1, 1);
        for (int x = 0; x < size.getX(); x++) for (int y = 0; y < size.getY(); y++)
            for (int z = 0; z < size.getZ(); z++) {
                boolean wall = x == 0 || x == 2 || z == 0 || z == 2;
                boolean doorway = x == 1 && z == 0 && (y == 1 || y == 2);
                BlockState state = !house || y == 0 || y == 3 || (wall && !doorway)
                        ? Blocks.OAK_PLANKS.defaultBlockState() : Blocks.AIR.defaultBlockState();
                helper.setBlock(PROTOTYPE.offset(x, y, z), state);
            }
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath("wayside", "construction_test_" + name);
        var template = helper.getLevel().getStructureManager().getOrCreate(id);
        template.fillFromWorld(helper.getLevel(), helper.absolutePos(PROTOTYPE), size, false, Blocks.STRUCTURE_VOID);
        return new BuildingInstance(UUID.randomUUID(), id, helper.absolutePos(ORIGIN), Rotation.NONE);
    }

    private static void requireComplete(GameTestHelper helper, NpcEntity npc, BuildingInstance building) {
        helper.onEachTick(() -> {
            if (npc.getTaskRunner().state() == NpcTaskRunner.State.FAILED) {
                var data = BuildingBlueprint.load(helper.getLevel(), building.blueprintId()).orElseThrow();
                var plan = ConstructionPlanner.createPlan(helper.getLevel(), data, building, ConstructionPlanner.AirPolicy.CLEAR);
                Wayside.LOGGER.error("Unfinished construction {} at {}: {}", building.blueprintId(), npc.position(), plan.tasks());
            }
            helper.assertTrue(npc.getTaskRunner().state() != NpcTaskRunner.State.FAILED,
                    "Construction failed: " + npc.getTaskRunner().description() + " at " + npc.position());
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(npc.getTaskRunner().state() == NpcTaskRunner.State.COMPLETED,
                    "Construction should complete: " + npc.getTaskRunner().description());
            var data = BuildingBlueprint.load(helper.getLevel(), building.blueprintId()).orElseThrow();
            helper.assertTrue(ConstructionPlanner.createPlan(helper.getLevel(), data, building,
                    ConstructionPlanner.AirPolicy.CLEAR).isComplete(), "World should match blueprint");
        });
    }

    @GameTest(template = "empty", timeoutTicks = 1400)
    public static void walksClearsAndBuildsHut(GameTestHelper helper) {
        NpcEntity npc = prepare(helper);
        BuildingInstance building = blueprint(helper, "hut", true);
        helper.setBlock(ORIGIN.above(), Blocks.STONE); // 墙体位置：先拆再放。
        helper.setBlock(ORIGIN.offset(1, 1, 1), Blocks.STONE); // 蓝图内部空气：拆除。
        npc.getInventory().setItem(0, new ItemStack(Items.OAK_PLANKS, 32));
        npc.getTaskRunner().setTask(new BuildStructureTask(building));
        requireComplete(helper, npc, building);
    }

    @GameTest(template = "empty", timeoutTicks = 2000)
    public static void loadsAndBuildsBundledStructureFile(GameTestHelper helper) {
        NpcEntity npc = prepare(helper);
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath("wayside", "test_house");
        BuildingBlueprint data = BuildingBlueprint.load(helper.getLevel(), id).orElseThrow();
        BuildingInstance building = new BuildingInstance(UUID.randomUUID(), id, helper.absolutePos(ORIGIN), Rotation.NONE);
        var plan = ConstructionPlanner.createPlan(helper.getLevel(), data, building, ConstructionPlanner.AirPolicy.CLEAR);
        var materials = BuildingMaterialCalculator.calculate(plan);
        helper.assertTrue(materials.canSupply(), "Bundled structure must contain supported construction data");
        int slot = 0;
        for (var entry : materials.requiredItems().entrySet()) {
            int remaining = entry.getValue();
            while (remaining > 0) {
                helper.assertTrue(slot < npc.getInventory().getContainerSize(), "Fixture materials must fit the NPC inventory");
                int count = Math.min(remaining, entry.getKey().getDefaultMaxStackSize());
                npc.getInventory().setItem(slot++, new ItemStack(entry.getKey(), count));
                remaining -= count;
            }
        }
        npc.getTaskRunner().setTask(new BuildStructureTask(building));
        requireComplete(helper, npc, building);
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void missingMaterialsDoNotDestroyObstruction(GameTestHelper helper) {
        NpcEntity npc = prepare(helper);
        BuildingInstance building = blueprint(helper, "missing", false);
        helper.setBlock(ORIGIN, Blocks.STONE);
        npc.getTaskRunner().setTask(new BuildStructureTask(building));
        helper.runAtTickTime(30, () -> {
            helper.assertTrue(npc.getTaskRunner().lastResult().failure() == ActionResult.Failure.MISSING_INGREDIENT,
                    "Should report missing materials");
            helper.assertBlockPresent(Blocks.STONE, ORIGIN);
            helper.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 1400)
    public static void resumesFromTaskSaveWithoutDoubleCharging(GameTestHelper helper) {
        NpcEntity npc = prepare(helper);
        BuildingInstance building = blueprint(helper, "resume", true);
        npc.getInventory().setItem(0, new ItemStack(Items.OAK_PLANKS, 32));
        npc.getTaskRunner().setTask(new BuildStructureTask(building));
        helper.runAtTickTime(50, () -> {
            CompoundTag saved = new CompoundTag();
            saved.put("WaysideTask", npc.getTaskRunner().save());
            npc.getTaskRunner().load(saved);
            helper.assertTrue(npc.getTaskRunner().description().startsWith(BuildStructureTask.ID),
                    "Task type must survive reload");
        });
        requireComplete(helper, npc, building);
    }

    @GameTest(template = "empty", timeoutTicks = 2000)
    public static void newNpcAutomaticallyBuildsHutWithFiniteStarterSupplies(GameTestHelper helper) {
        NpcEntity npc = prepareAutomaticNpc(helper);
        // 不交付材料，不派发任务，不调用任何建造命令。
        helper.succeedWhen(() -> assertAutomaticHut(helper, npc));
    }

    @GameTest(template = "empty", timeoutTicks = 2000)
    public static void automaticHutSurvivesEntityReloadWithoutRefilling(GameTestHelper helper) {
        NpcEntity npc = prepareAutomaticNpc(helper);
        helper.runAtTickTime(40, () -> {
            CompoundTag saved = new CompoundTag();
            npc.addAdditionalSaveData(saved);
            int logs = npc.getLogCount();
            int planks = npc.getPlankCount();
            int stairs = npc.getInventory().countItem(Items.OAK_STAIRS);
            npc.readAdditionalSaveData(saved);
            helper.assertTrue(npc.getLogCount() == logs && npc.getPlankCount() == planks
                            && npc.getInventory().countItem(Items.OAK_STAIRS) == stairs,
                    "Entity reload must preserve exact remaining stock");
        });
        helper.succeedWhen(() -> assertAutomaticHut(helper, npc));
    }

    @GameTest(template = "empty", timeoutTicks = 900)
    public static void buildingNeverReplacesFullInventoryWithStarterSupplies(GameTestHelper helper) {
        NpcEntity npc = prepareAutomaticNpc(helper);
        for (int i = 0; i < npc.getInventory().getContainerSize(); i++) {
            npc.getInventory().setItem(i, new ItemStack(Items.STONE, 64));
        }
        helper.succeedWhen(() -> {
            helper.assertTrue(npc.getTaskRunner().lastResult().failure() == ActionResult.Failure.MISSING_INGREDIENT,
                    "Building must report missing construction materials, not attempt another starter grant");
            helper.assertTrue(npc.getInventory().countItem(Items.STONE) == 576
                            && npc.getLogCount() == 0 && npc.getPlankCount() == 0,
                    "Building must leave a full inventory untouched");
        });
    }

    private static NpcEntity prepareAutomaticNpc(GameTestHelper helper) {
        for (int x = 0; x < 15; x++) for (int z = 0; z < 13; z++) helper.setBlock(x, 0, z, Blocks.STONE);
        return helper.spawn(ModEntities.NPC.get(), new BlockPos(2, 1, 5));
    }

    private static void assertAutomaticHut(GameTestHelper helper, NpcEntity npc) {
        helper.assertTrue(npc.getTaskRunner().state() == NpcTaskRunner.State.COMPLETED,
                "Automatic hut should complete: " + npc.getTaskRunner().description());
        CompoundTag saved = npc.getTaskRunner().save();
        helper.assertTrue(saved.getString("Type").equals(BuildHutTask.ID), "Default task must be the autonomous hut goal");
        CompoundTag construction = saved.getCompound("Data").getCompound("Construction");
        var building = new BuildingManager(helper.getLevel()).getBuildings().stream()
                .filter(value -> value.id().equals(construction.getUUID("Building"))).findFirst().orElseThrow();
        var data = BuildingBlueprint.load(helper.getLevel(), building.blueprintId()).orElseThrow();
        var plan = ConstructionPlanner.createPlan(helper.getLevel(), data, building, ConstructionPlanner.AirPolicy.CLEAR);
        helper.assertTrue(plan.isComplete(), "Autonomously selected site should match the blueprint");
        var completeCost = BuildingMaterialCalculator.calculate(new ConstructionPlanner.Plan(
                data.getBlocks().stream().filter(info -> !info.state().isAir()).map(info -> new ConstructionOperation(
                        building.toWorldPos(info.pos()), info.state(), ConstructionOperation.Action.PLACE)).toList(), 0, 0));
        helper.assertTrue(npc.getLogCount() + completeCost.requiredItems().getOrDefault(Items.OAK_LOG, 0) == 16
                        && npc.getPlankCount() + completeCost.requiredItems().getOrDefault(Items.OAK_PLANKS, 0) == 64
                        && npc.getInventory().countItem(Items.OAK_STAIRS)
                        + completeCost.requiredItems().getOrDefault(Items.OAK_STAIRS, 0) == 64,
                "Finished building must consume the finite initial stock exactly once");
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void rejectedPlacementKeepsInventoryAndWorld(GameTestHelper helper) {
        NpcEntity npc = prepare(helper);
        BlockPos target = helper.absolutePos(new BlockPos(3, 1, 5));
        npc.getInventory().setItem(0, new ItemStack(Items.GLASS, 2));
        Consumer<BlockEvent.EntityPlaceEvent> listener = event -> {
            if (event.getEntity() == npc && event.getPos().equals(target)) event.setCanceled(true);
        };
        NeoForge.EVENT_BUS.addListener(listener);
        try {
            var result = new PlaceBlockAction(target, Blocks.GLASS.defaultBlockState()).tick(npc);
            helper.assertTrue(result.failure() == ActionResult.Failure.FORBIDDEN,
                    "Canceled placement must fail");
            helper.assertTrue(helper.getLevel().getBlockState(target).isAir(), "Temporary block must be rolled back");
            helper.assertTrue(npc.getInventory().countItem(Items.GLASS) == 2, "Canceled placement must not consume glass");
        } finally { NeoForge.EVENT_BUS.unregister(listener); }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void genericPlacementConsumesExactCompositeCost(GameTestHelper helper) {
        NpcEntity npc = prepare(helper);
        BlockPos target = helper.absolutePos(new BlockPos(3, 1, 5));
        npc.getInventory().setItem(0, new ItemStack(Items.OAK_SLAB, 2));
        BlockState state = Blocks.OAK_SLAB.defaultBlockState()
                .setValue(BlockStateProperties.SLAB_TYPE, SlabType.DOUBLE);
        var action = new PlaceBlockAction(target, state);
        helper.assertTrue(action.tick(npc).isSuccess(), "Should place a double slab");
        helper.assertTrue(npc.getInventory().countItem(Items.OAK_SLAB) == 0, "Should consume two slabs");
        helper.assertTrue(action.tick(npc).isSuccess(), "Completed placement must be idempotent");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void rejectsStandingInsideFutureBlock(GameTestHelper helper) {
        NpcEntity npc = prepare(helper);
        npc.getInventory().setItem(0, new ItemStack(Items.GLASS, 1));
        var result = new PlaceBlockAction(npc.blockPosition(), Blocks.GLASS.defaultBlockState()).tick(npc);
        helper.assertTrue(result.isFailed(), "NPC must move out before placing into its own body");
        helper.assertTrue(npc.getInventory().countItem(Items.GLASS) == 1, "Rejected placement must retain material");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void existingFenceConnectionsDoNotDemandAnotherItem(GameTestHelper helper) {
        NpcEntity npc = prepare(helper);
        BuildingInstance building = blueprint(helper, "fence", false);
        var state = Blocks.OAK_FENCE.defaultBlockState().setValue(BlockStateProperties.NORTH, true);
        helper.setBlock(PROTOTYPE, state);
        helper.getLevel().getStructureManager().getOrCreate(building.blueprintId()).fillFromWorld(
                helper.getLevel(), helper.absolutePos(PROTOTYPE), new Vec3i(1, 1, 1), false, Blocks.STRUCTURE_VOID);
        helper.setBlock(ORIGIN, Blocks.OAK_FENCE);
        var data = BuildingBlueprint.load(helper.getLevel(), building.blueprintId()).orElseThrow();
        helper.assertTrue(ConstructionPlanner.createPlan(helper.getLevel(), data, building,
                ConstructionPlanner.AirPolicy.CLEAR).isComplete(), "Neighbor-derived connections must not require replacement");
        helper.succeed();
    }
}
