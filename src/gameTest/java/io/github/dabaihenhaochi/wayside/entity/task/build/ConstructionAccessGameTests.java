package io.github.dabaihenhaochi.wayside.entity.task.build;

import io.github.dabaihenhaochi.wayside.Wayside;
import io.github.dabaihenhaochi.wayside.building.BuildingInstance;
import io.github.dabaihenhaochi.wayside.building.BuildingManager;
import io.github.dabaihenhaochi.wayside.building.BuildingSavedData;
import io.github.dabaihenhaochi.wayside.building.ConstructionOperation;
import io.github.dabaihenhaochi.wayside.building.ConstructionPlanner;
import io.github.dabaihenhaochi.wayside.entity.NpcEntity;
import io.github.dabaihenhaochi.wayside.entity.action.ActionResult;
import io.github.dabaihenhaochi.wayside.entity.ai.NpcBlockInteraction;
import io.github.dabaihenhaochi.wayside.entity.task.NpcTask;
import io.github.dabaihenhaochi.wayside.entity.task.NpcTaskRunner;
import io.github.dabaihenhaochi.wayside.registry.ModEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

@GameTestHolder(Wayside.MODID)
@PrefixGameTestTemplate(false)
public final class ConstructionAccessGameTests {
    private static final BlockPos FEET = new BlockPos(5, 1, 5);
    private static final BlockPos TARGET = new BlockPos(7, 5, 5);

    private static NpcEntity prepare(GameTestHelper helper, boolean cliff) {
        for (int x = 0; x < 13; x++) for (int z = 0; z < 12; z++) {
            helper.setBlock(x, 0, z, Blocks.STONE);
            helper.setBlock(x, 6, z, Blocks.AIR); // 清除测试框架顶盖，给高处站位留下身体空间。
        }
        if (cliff) {
            for (int x = 6; x < 12; x++) for (int z = 0; z < 12; z++)
                for (int y = 1; y <= 4; y++) helper.setBlock(x, y, z, Blocks.STONE);
        }
        NpcEntity npc = helper.spawn(ModEntities.NPC.get(), FEET);
        npc.getTaskRunner().cancel();
        npc.getInventory().clearContent();
        npc.getInventory().setItem(0, new ItemStack(Items.OAK_PLANKS, 1));
        npc.getInventory().setItem(1, new ItemStack(Items.DIRT, 8));
        npc.setOnGround(true);
        return npc;
    }

    private static void runTerrainPlacement(GameTestHelper helper, NpcEntity npc, BlockPos target,
                                            Predicate<BlockPos> accessAllowed, ConstructionExecutor executor) {
        BlockPos worldTarget = helper.absolutePos(target);
        npc.getTaskRunner().setTask(new NpcTask() {
            @Override public String typeId() { return "wayside:test_access_policy"; }
            @Override public String stageName() { return executor.stageName(); }
            @Override public ActionResult tick(NpcEntity actor) {
                return executor.tick(actor, () -> {
                    var remaining = actor.level().getBlockState(worldTarget).is(Blocks.OAK_PLANKS)
                            ? List.<ConstructionOperation>of()
                            : List.of(new ConstructionOperation(worldTarget, Blocks.OAK_PLANKS.defaultBlockState(),
                                    ConstructionOperation.Action.PLACE));
                    return new ConstructionPlanner.Plan(remaining, 0, 0);
                }, accessAllowed);
            }
            @Override public void pause(NpcEntity actor) { executor.pause(actor); }
        });
    }

    private static UUID reserve(GameTestHelper helper, BlockPos pos) {
        UUID id = UUID.randomUUID();
        var building = new BuildingInstance(id, ResourceLocation.fromNamespaceAndPath("wayside", "test_reserved_space"),
                helper.absolutePos(pos), Rotation.NONE);
        BuildingSavedData.get(helper.getLevel()).add(building, new BoundingBox(helper.absolutePos(pos)));
        return id;
    }

    private static void assertNoPillar(GameTestHelper helper, NpcEntity npc) {
        helper.assertBlockPresent(Blocks.AIR, FEET);
        helper.assertTrue(npc.getInventory().countItem(Items.DIRT) == 8,
                "Ineligible recovery must not place or consume access dirt");
    }

    @GameTest(template = "empty", timeoutTicks = 350)
    public static void strictTerrainRaisesOnlyAfterRealPathFailure(GameTestHelper helper) {
        NpcEntity npc = prepare(helper, true);
        var search = NpcBlockInteraction.searchPlacementPosition(npc, helper.absolutePos(TARGET), Blocks.OAK_PLANKS.defaultBlockState());
        helper.assertTrue(search.stand().isEmpty() && search.navigationFailed(),
                "Cliff fixture must offer valid stations whose paths actually fail: " + search);
        runTerrainPlacement(helper, npc, TARGET, pos -> !pos.equals(helper.absolutePos(TARGET)), new ConstructionExecutor(true));
        helper.succeedWhen(() -> {
            helper.assertTrue(npc.getTaskRunner().state() == NpcTaskRunner.State.COMPLETED,
                    "Strict terrain work must recover after a useful external pillar: " + npc.getTaskRunner().description());
            helper.assertBlockPresent(Blocks.OAK_PLANKS, TARGET);
            int dirt = 0;
            for (int y = 1; y <= 5; y++) if (helper.getBlockState(new BlockPos(5, y, 5)).is(Blocks.DIRT)) dirt++;
            helper.assertTrue(dirt > 0 && dirt + npc.getInventory().countItem(Items.DIRT) == 8,
                    "Only the useful external access column may consume dirt");
        });
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void missingGeometricStationDoesNotCountAsPathFailure(GameTestHelper helper) {
        NpcEntity npc = prepare(helper, false);
        // 独立高柱没有可站立的施工位置；垫高可能改善视线，但没有实际失败的路径。
        BlockPos target = new BlockPos(7, 7, 5);
        for (int y = 1; y < 7; y++) helper.setBlock(7, y, 5, Blocks.STONE);
        var search = NpcBlockInteraction.searchPlacementPosition(npc, helper.absolutePos(target), Blocks.OAK_PLANKS.defaultBlockState());
        helper.assertTrue(search.stand().isEmpty() && !search.navigationFailed(),
                "No geometric station must remain distinct from a failed navigation attempt: " + search);
        runTerrainPlacement(helper, npc, target, pos -> true, new ConstructionExecutor(true));
        helper.runAtTickTime(60, () -> {
            assertNoPillar(helper, npc);
            helper.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void currentSiteProtectionRejectsPillarBase(GameTestHelper helper) {
        NpcEntity npc = prepare(helper, true);
        runTerrainPlacement(helper, npc, TARGET, pos -> !pos.equals(helper.absolutePos(FEET)), new ConstructionExecutor(true));
        helper.runAtTickTime(60, () -> {
            assertNoPillar(helper, npc);
            helper.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void anotherBuildingReservationRejectsPillarBase(GameTestHelper helper) {
        NpcEntity npc = prepare(helper, true);
        UUID id = reserve(helper, FEET);
        runTerrainPlacement(helper, npc, TARGET, pos -> true, new ConstructionExecutor(true));
        helper.runAtTickTime(60, () -> {
            try { assertNoPillar(helper, npc); }
            finally { new BuildingManager(helper.getLevel()).remove(id); }
            helper.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void futurePillarCellInsideReservationPreventsStartingColumn(GameTestHelper helper) {
        NpcEntity npc = prepare(helper, true);
        UUID id = reserve(helper, FEET.above());
        runTerrainPlacement(helper, npc, TARGET, pos -> true, new ConstructionExecutor(true));
        helper.runAtTickTime(60, () -> {
            try { assertNoPillar(helper, npc); }
            finally { new BuildingManager(helper.getLevel()).remove(id); }
            helper.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 140)
    public static void newReservationDuringJumpStopsPlacementBeforeCharging(GameTestHelper helper) {
        NpcEntity npc = prepare(helper, true);
        runTerrainPlacement(helper, npc, TARGET, pos -> true, new ConstructionExecutor(true));
        UUID[] reservation = {null};
        helper.onEachTick(() -> {
            if (reservation[0] == null && npc.getTaskRunner().description().contains("RAISING")
                    && npc.getY() > helper.absolutePos(FEET).getY() + 0.1) reservation[0] = reserve(helper, FEET);
        });
        helper.runAtTickTime(90, () -> {
            try {
                helper.assertTrue(reservation[0] != null, "Fixture must reserve the space after the physical jump begins");
                assertNoPillar(helper, npc);
            } finally {
                if (reservation[0] != null) new BuildingManager(helper.getLevel()).remove(reservation[0]);
            }
            helper.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void savedRiseLimitStillAppliesToStrictRecovery(GameTestHelper helper) {
        NpcEntity npc = prepare(helper, true);
        ConstructionExecutor executor = new ConstructionExecutor(true);
        CompoundTag checkpoint = new CompoundTag();
        checkpoint.putInt("ConsecutiveRise", 8);
        executor.load(checkpoint);
        runTerrainPlacement(helper, npc, TARGET, pos -> true, executor);
        helper.runAtTickTime(60, () -> {
            assertNoPillar(helper, npc);
            helper.assertTrue(executor.save().getInt("ConsecutiveRise") == 8,
                    "Failed navigation alone must not reset a saved rise limit");
            helper.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void accessPillarCannotSpendDirtReservedForTerrain(GameTestHelper helper) {
        NpcEntity npc = prepare(helper, true);
        npc.getInventory().setItem(0, ItemStack.EMPTY);
        npc.getInventory().setItem(1, new ItemStack(Items.DIRT, 1));
        BlockPos target = helper.absolutePos(TARGET);
        ConstructionExecutor executor = new ConstructionExecutor(true);
        npc.getTaskRunner().setTask(new NpcTask() {
            @Override public String typeId() { return "wayside:test_reserved_access_material"; }
            @Override public String stageName() { return executor.stageName(); }
            @Override public ActionResult tick(NpcEntity actor) {
                return executor.tick(actor, () -> new ConstructionPlanner.Plan(List.of(
                        new ConstructionOperation(target, Blocks.DIRT.defaultBlockState(), ConstructionOperation.Action.PLACE)),
                        0, 0), pos -> true);
            }
            @Override public void pause(NpcEntity actor) { executor.pause(actor); }
        });
        helper.runAtTickTime(60, () -> {
            helper.assertBlockPresent(Blocks.AIR, FEET);
            helper.assertTrue(npc.getInventory().countItem(Items.DIRT) == 1,
                    "Strict navigation recovery must reserve all terrain material");
            helper.succeed();
        });
    }
}
