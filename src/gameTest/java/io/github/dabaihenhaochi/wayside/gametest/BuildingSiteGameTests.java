package io.github.dabaihenhaochi.wayside.gametest;

import io.github.dabaihenhaochi.wayside.entity.task.build.BuildHutTask;
import io.github.dabaihenhaochi.wayside.entity.task.build.BuildStructureTask;
import io.github.dabaihenhaochi.wayside.entity.task.build.PrepareBuildingSiteTask;

import io.github.dabaihenhaochi.wayside.Wayside;
import io.github.dabaihenhaochi.wayside.building.*;
import io.github.dabaihenhaochi.wayside.entity.NpcEntity;
import io.github.dabaihenhaochi.wayside.entity.action.ActionResult;
import io.github.dabaihenhaochi.wayside.entity.task.*;
import io.github.dabaihenhaochi.wayside.registry.ModEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

@GameTestHolder(Wayside.MODID)
@PrefixGameTestTemplate(false)
public final class BuildingSiteGameTests {
    private static final BlockPos ORIGIN = new BlockPos(7, 3, 5);

    private static BuildingInstance blueprint(GameTestHelper helper, String name, Vec3i size) {
        BlockPos prototype = new BlockPos(1, 8, 1);
        for (BlockPos pos : BlockPos.betweenClosed(prototype, prototype.offset(size).offset(-1, -1, -1))) {
            helper.setBlock(pos, pos.getY() == prototype.getY() ? Blocks.OAK_PLANKS : Blocks.AIR);
        }
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath("wayside", "site_test_" + name);
        helper.getLevel().getStructureManager().getOrCreate(id).fillFromWorld(
                helper.getLevel(), helper.absolutePos(prototype), size, false, Blocks.STRUCTURE_VOID);
        return new BuildingInstance(UUID.randomUUID(), id, helper.absolutePos(ORIGIN), Rotation.NONE);
    }

    private static NpcEntity slope(GameTestHelper helper, boolean automatic) {
        for (int x = 0; x < 16; x++) for (int z = 0; z < 14; z++) {
            helper.setBlock(x, 0, z, Blocks.STONE);
            helper.setBlock(x, 1, z, Blocks.STONE);
            helper.setBlock(x, 2, z, x <= 7 ? Blocks.STONE : Blocks.AIR);
        }
        NpcEntity npc = helper.spawn(ModEntities.NPC.get(), new BlockPos(2, 3, 5));
        if (!automatic) {
            npc.getTaskRunner().cancel();
            npc.getInventory().clearContent(); // 独立整地测试明确设置自身所需物资。
        }
        return npc;
    }

    private static BuildingSitePlan plan(GameTestHelper helper, BuildingInstance building) {
        return BuildingSitePlanner.plan(helper.getLevel(), BuildingBlueprint.load(helper.getLevel(), building.blueprintId()).orElseThrow(),
                building, 2, 2).orElseThrow();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void plansSlopeWithoutChangingWorldAndCountsDirt(GameTestHelper helper) {
        NpcEntity npc = slope(helper, false);
        BuildingInstance building = blueprint(helper, "budget", new Vec3i(3, 3, 3));
        helper.setBlock(ORIGIN.above(), Blocks.STONE);
        var site = plan(helper, building);
        var remaining = site.remaining(helper.getLevel());
        var cost = BuildingMaterialCalculator.calculate(remaining);
        helper.assertTrue(site.fill().size() == 6 && cost.requiredItems().getOrDefault(Items.DIRT, 0) == 6,
                "Two low columns across three rows must require six dirt");
        helper.assertTrue(npc.getInventory().isEmpty(), "Planning must not grant resources");
        helper.assertBlockPresent(Blocks.STONE, ORIGIN.above());
        helper.assertBlockPresent(Blocks.AIR, ORIGIN.offset(1, -1, 0));
        var restored = BuildingSitePlan.load(site.save());
        helper.assertTrue(restored.fill().equals(site.fill())
                        && restored.remaining(helper.getLevel()).tasks().size() == remaining.tasks().size(),
                "Site data must round-trip without changing the work");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 1600)
    public static void fillsTwoLevelSlopeClearsAndResumesThenBuilds(GameTestHelper helper) {
        NpcEntity npc = slope(helper, false);
        BuildingInstance building = blueprint(helper, "execution", new Vec3i(3, 3, 3));
        // 第二级缓坡：最右一列需要从一格低的实地连续补两层。
        for (int z = 5; z <= 7; z++) helper.setBlock(9, 1, z, Blocks.AIR);
        helper.setBlock(ORIGIN, Blocks.STONE);
        helper.setBlock(ORIGIN.above(), Blocks.STONE);
        var site = plan(helper, building);
        helper.assertTrue(site.fill().size() == 9, "Fixture needs a two-level foundation");
        npc.getInventory().setItem(0, new ItemStack(Items.DIRT, 9));
        npc.getInventory().setItem(1, new ItemStack(Items.OAK_PLANKS, 9));
        npc.getTaskRunner().setTask(new PrepareBuildingSiteTask(site));
        boolean[] buildingStarted = {false};
        helper.runAtTickTime(65, () -> {
            CompoundTag tag = new CompoundTag();
            tag.put("WaysideTask", npc.getTaskRunner().save());
            npc.getTaskRunner().load(tag);
            helper.assertTrue(npc.getTaskRunner().description().startsWith(PrepareBuildingSiteTask.ID),
                    "Reload must restore the terrain task");
        });
        helper.onEachTick(() -> {
            helper.assertTrue(npc.getTaskRunner().state() != NpcTaskRunner.State.FAILED,
                    "Terrain/build failed: " + npc.getTaskRunner().description() + " at " + npc.position());
            if (!buildingStarted[0]) {
                helper.assertTrue(npc.getInventory().countItem(Items.OAK_PLANKS) == 9,
                        "No house material may be spent during preparation");
                for (BlockPos pos : site.fill()) {
                    if (helper.getLevel().getBlockState(pos).is(Blocks.DIRT)) {
                        helper.assertTrue(helper.getLevel().getBlockState(pos.below()).isFaceSturdy(
                                helper.getLevel(), pos.below(), net.minecraft.core.Direction.UP), "Every dirt block must have support");
                    }
                }
                if (npc.getTaskRunner().state() == NpcTaskRunner.State.COMPLETED) {
                    helper.assertTrue(site.remaining(helper.getLevel()).isComplete(), "All terrain must be finished before building");
                    helper.assertTrue(npc.getInventory().countItem(Items.DIRT) == 0, "Reload must not double-charge dirt");
                    buildingStarted[0] = true;
                    npc.getTaskRunner().setTask(new BuildStructureTask(building));
                }
            }
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(buildingStarted[0] && npc.getTaskRunner().state() == NpcTaskRunner.State.COMPLETED,
                    "Prepared site must support subsequent blueprint construction");
            helper.assertTrue(npc.getInventory().countItem(Items.OAK_PLANKS) == 0, "Nine floor blocks must cost nine planks");
        });
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void missingDirtLeavesObstaclesUntouched(GameTestHelper helper) {
        NpcEntity npc = slope(helper, false);
        BuildingInstance building = blueprint(helper, "no_dirt", new Vec3i(3, 3, 3));
        helper.setBlock(ORIGIN, Blocks.STONE);
        npc.getTaskRunner().setTask(new PrepareBuildingSiteTask(plan(helper, building)));
        helper.succeedWhen(() -> {
            helper.assertTrue(npc.getTaskRunner().state() == NpcTaskRunner.State.FAILED, "Should fail before demolition");
            helper.assertTrue(npc.getTaskRunner().lastResult().failure() == ActionResult.Failure.MISSING_INGREDIENT,
                    "Failure must explain missing dirt");
            helper.assertBlockPresent(Blocks.STONE, ORIGIN);
        });
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void rejectsDeepHolesWaterBlockEntitiesAndDeepCuts(GameTestHelper helper) {
        slope(helper, false);
        BuildingInstance building = blueprint(helper, "unsafe", new Vec3i(1, 3, 1));
        var blueprint = BuildingBlueprint.load(helper.getLevel(), building.blueprintId()).orElseThrow();
        helper.setBlock(ORIGIN.below(), Blocks.AIR);
        helper.setBlock(ORIGIN.below(2), Blocks.AIR);
        helper.assertTrue(BuildingSitePlanner.plan(helper.getLevel(), blueprint, building, 1, 2).isEmpty(), "Reject excessive fill depth");
        helper.setBlock(ORIGIN.below(), Blocks.WATER);
        helper.assertTrue(BuildingSitePlanner.plan(helper.getLevel(), blueprint, building, 2, 2).isEmpty(), "Reject water foundations");
        helper.setBlock(ORIGIN.below(), Blocks.CHEST);
        helper.assertTrue(BuildingSitePlanner.plan(helper.getLevel(), blueprint, building, 2, 2).isEmpty(), "Do not bury block entities");
        helper.setBlock(ORIGIN.below(), Blocks.STONE);
        helper.setBlock(ORIGIN, Blocks.BEDROCK);
        helper.assertTrue(BuildingSitePlanner.plan(helper.getLevel(), blueprint, building, 2, 2).isEmpty(), "Reject unbreakable obstruction");
        helper.setBlock(ORIGIN, Blocks.STONE);
        helper.setBlock(ORIGIN.above(), Blocks.STONE);
        helper.setBlock(ORIGIN.above(2), Blocks.STONE);
        helper.assertTrue(BuildingSitePlanner.plan(helper.getLevel(), blueprint, building, 2, 2).isEmpty(), "Reject a deep continuous cut");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void lostFoundationSupportStopsBeforeSpending(GameTestHelper helper) {
        NpcEntity npc = slope(helper, false);
        BuildingInstance building = blueprint(helper, "support_lost", new Vec3i(3, 3, 3));
        var site = plan(helper, building);
        helper.setBlock(8, 1, 5, Blocks.AIR);
        npc.getInventory().setItem(0, new ItemStack(Items.DIRT, 6));
        npc.getTaskRunner().setTask(new PrepareBuildingSiteTask(site));
        helper.succeedWhen(() -> {
            helper.assertTrue(npc.getTaskRunner().state() == NpcTaskRunner.State.FAILED, "Invalidated foundation must stop");
            helper.assertTrue(npc.getInventory().countItem(Items.DIRT) == 6, "No unsupported fill may spend dirt");
        });
    }

    @GameTest(template = "empty", timeoutTicks = 3000)
    public static void autonomousHutLevelsTerrainBeforeBuildingAndSurvivesReload(GameTestHelper helper) {
        for (int x = 0; x < 16; x++) for (int z = 0; z < 14; z++) {
            helper.setBlock(x, -1, z, Blocks.STONE);
            helper.setBlock(x, 0, z, x % 3 == 0 ? Blocks.AIR : Blocks.STONE);
        }
        NpcEntity npc = helper.spawn(ModEntities.NPC.get(), new BlockPos(2, 1, 5));
        helper.runAtTickTime(60, () -> {
            int dirt = npc.getInventory().countItem(Items.DIRT);
            CompoundTag tag = new CompoundTag();
            npc.addAdditionalSaveData(tag);
            npc.readAdditionalSaveData(tag);
            helper.assertTrue(npc.getInventory().countItem(Items.DIRT) == dirt, "Reload must not replenish dirt");
        });
        helper.onEachTick(() -> {
            if (npc.getTaskRunner().state() == NpcTaskRunner.State.FAILED) {
                var failed = npc.getTaskRunner().save().getCompound("Data");
                var construction = failed.getCompound("Construction");
                var building = new BuildingManager(helper.getLevel()).getBuildings().stream()
                        .filter(value -> value.id().equals(construction.getUUID("Building"))).findFirst().orElseThrow();
                Wayside.LOGGER.error("Slope hut failed: npc={} site={} pending={}", npc.position(), failed.getCompound("Preparation"),
                        ConstructionPlanner.createPlan(helper.getLevel(), BuildingBlueprint.load(helper.getLevel(), building.blueprintId()).orElseThrow(),
                                building, ConstructionPlanner.AirPolicy.CLEAR));
            }
            helper.assertTrue(npc.getTaskRunner().state() != NpcTaskRunner.State.FAILED,
                    "Automatic slope construction failed: " + npc.getTaskRunner().description());
            var data = npc.getTaskRunner().save().getCompound("Data");
            if (data.contains("Preparation") && !data.getBoolean("Prepared")) {
                helper.assertTrue(npc.getInventory().countItem(Items.OAK_LOG) == 16
                                && npc.getInventory().countItem(Items.OAK_PLANKS) == 64
                                && npc.getInventory().countItem(Items.OAK_STAIRS) == 64,
                        "Building must not start until the entire site is prepared");
            }
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(npc.getTaskRunner().state() == NpcTaskRunner.State.COMPLETED, "Automatic slope hut must finish");
            var data = npc.getTaskRunner().save().getCompound("Data");
            var site = BuildingSitePlan.load(data.getCompound("Preparation"));
            helper.assertTrue(!site.fill().isEmpty(), "This site must actually need dirt fill");
            helper.assertTrue(npc.getInventory().countItem(Items.DIRT) + site.fill().size() == 64,
                    "Foundation must consume the fixed initial dirt exactly once");
            var building = new BuildingManager(helper.getLevel()).getBuildings().stream()
                    .filter(value -> value.id().equals(data.getCompound("Construction").getUUID("Building"))).findFirst().orElseThrow();
            helper.assertTrue(ConstructionPlanner.createPlan(helper.getLevel(),
                    BuildingBlueprint.load(helper.getLevel(), building.blueprintId()).orElseThrow(), building,
                    ConstructionPlanner.AirPolicy.CLEAR).isComplete(), "Completed house must match its blueprint");
        });
    }

    @GameTest(template = "empty", timeoutTicks = 900)
    public static void totalBudgetProtectsTerrainWhenHouseMaterialsAreMissing(GameTestHelper helper) {
        NpcEntity npc = slope(helper, true);
        npc.getInventory().clearContent();
        npc.getInventory().setItem(0, new ItemStack(Items.DIRT, 64));
        helper.setBlock(5, 3, 5, Blocks.STONE);
        helper.succeedWhen(() -> {
            helper.assertTrue(npc.getTaskRunner().state() == NpcTaskRunner.State.FAILED, "Site search should report missing house stock");
            helper.assertTrue(npc.getTaskRunner().lastResult().failure() == ActionResult.Failure.MISSING_INGREDIENT,
                    "Dirt alone must not authorize demolition without house materials");
            helper.assertBlockPresent(Blocks.STONE, new BlockPos(5, 3, 5));
            helper.assertTrue(npc.getInventory().countItem(Items.DIRT) == 64, "Budget failure must leave all dirt untouched");
        });
    }

    @GameTest(template = "empty", timeoutTicks = 2600)
    public static void automaticCutSlopeDoesNotLeaveBottomBlockBehindWalls(GameTestHelper helper) {
        for (int x = 0; x < 16; x++) for (int z = 0; z < 14; z++) {
            helper.setBlock(x, 0, z, Blocks.STONE);
            helper.setBlock(x, 1, z, Blocks.STONE);
            helper.setBlock(x, 2, z, x % 4 >= 2 ? Blocks.STONE : Blocks.AIR);
        }
        NpcEntity npc = helper.spawn(ModEntities.NPC.get(), new BlockPos(2, 3, 5));
        boolean[] hadEarthwork = {false};
        helper.onEachTick(() -> {
            helper.assertTrue(npc.getTaskRunner().state() != NpcTaskRunner.State.FAILED,
                    "Uneven ground must not trap unfinished floor blocks: " + npc.getTaskRunner().description());
            var data = npc.getTaskRunner().save().getCompound("Data");
            if (data.contains("Preparation") && !data.getBoolean("Prepared")) {
                hadEarthwork[0] |= !BuildingSitePlan.load(data.getCompound("Preparation")).remaining(helper.getLevel()).isComplete();
            }
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(npc.getTaskRunner().state() == NpcTaskRunner.State.COMPLETED && hadEarthwork[0],
                    "The uneven site must be prepared and the whole hut completed");
            var construction = npc.getTaskRunner().save().getCompound("Data").getCompound("Construction");
            var building = new BuildingManager(helper.getLevel()).getBuildings().stream()
                    .filter(value -> value.id().equals(construction.getUUID("Building"))).findFirst().orElseThrow();
            helper.assertTrue(ConstructionPlanner.createPlan(helper.getLevel(),
                    BuildingBlueprint.load(helper.getLevel(), building.blueprintId()).orElseThrow(), building,
                    ConstructionPlanner.AirPolicy.CLEAR).isComplete(), "No bottom block may remain unfinished behind completed walls");
        });
    }

    @GameTest(template = "empty", timeoutTicks = 120)
    public static void rejectedDirtFillKeepsMaterialAndAir(GameTestHelper helper) {
        NpcEntity npc = slope(helper, false);
        BlockPos target = helper.absolutePos(new BlockPos(3, 2, 5));
        helper.setBlock(new BlockPos(3, 2, 5), Blocks.AIR);
        var site = new BuildingSitePlan(helper.getLevel().dimension().location(),
                new net.minecraft.world.level.levelgen.structure.BoundingBox(target.getX(), target.getY() + 1, target.getZ(),
                        target.getX(), target.getY() + 1, target.getZ()), java.util.Set.of(target));
        npc.getInventory().setItem(0, new ItemStack(Items.DIRT, 1));
        Consumer<BlockEvent.EntityPlaceEvent> listener = event -> {
            if (event.getEntity() == npc && event.getPos().equals(target)) event.setCanceled(true);
        };
        NeoForge.EVENT_BUS.addListener(listener);
        npc.getTaskRunner().setTask(new PrepareBuildingSiteTask(site));
        helper.runAtTickTime(90, () -> NeoForge.EVENT_BUS.unregister(listener));
        helper.runAtTickTime(91, () -> {
            helper.assertTrue(npc.getTaskRunner().lastResult().failure() == ActionResult.Failure.FORBIDDEN,
                    "Terrain placement must respect the same event as building placement");
            helper.assertTrue(npc.getInventory().countItem(Items.DIRT) == 1
                            && helper.getLevel().getBlockState(target).isAir(), "Rejected dirt must leave inventory and world intact");
            helper.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void reservesFoundationSpaceAndRejectsOtherDimension(GameTestHelper helper) {
        slope(helper, false);
        BuildingInstance building = blueprint(helper, "reservation", new Vec3i(3, 3, 3));
        var blueprint = BuildingBlueprint.load(helper.getLevel(), building.blueprintId()).orElseThrow();
        var site = plan(helper, building);
        var manager = new BuildingManager(helper.getLevel());
        helper.assertTrue(manager.tryRegister(blueprint, building.origin(), Rotation.NONE, site.reservedBounds()).isPresent(),
                "Registration should reserve the foundation too");
        helper.assertTrue(manager.tryRegister(blueprint, building.origin().below(3), Rotation.NONE).isEmpty(),
                "A lower building must not occupy the reserved foundation");
        CompoundTag saved = site.save();
        saved.putString("Dimension", "minecraft:the_nether");
        helper.assertTrue(!BuildingSitePlan.load(saved).remaining(helper.getLevel()).isComplete()
                        && BuildingSitePlan.load(saved).remaining(helper.getLevel()).unavailablePositions() > 0,
                "Saved coordinates must never execute in a different dimension");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void legacyHutSaveDoesNotClearAnAlreadyStartedHouse(GameTestHelper helper) {
        NpcEntity npc = slope(helper, false);
        BuildingInstance building = blueprint(helper, "legacy", new Vec3i(1, 1, 1));
        helper.setBlock(ORIGIN, Blocks.OAK_PLANKS);
        CompoundTag legacy = new CompoundTag();
        legacy.put("Construction", new BuildStructureTask(building).save());
        var task = new BuildHutTask();
        task.load(legacy);
        npc.getTaskRunner().setTask(task);
        helper.succeedWhen(() -> {
            helper.assertTrue(npc.getTaskRunner().state() == NpcTaskRunner.State.COMPLETED, "Old construction must resume directly");
            helper.assertBlockPresent(Blocks.OAK_PLANKS, ORIGIN);
            helper.assertTrue(npc.getInventory().isEmpty(), "Already completed legacy blocks must not cost new material");
        });
    }
}
