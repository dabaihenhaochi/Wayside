package io.github.dabaihenhaochi.wayside.entity.task.build;

import io.github.dabaihenhaochi.wayside.Wayside;
import io.github.dabaihenhaochi.wayside.building.*;
import io.github.dabaihenhaochi.wayside.entity.NpcEntity;
import io.github.dabaihenhaochi.wayside.entity.action.ActionResult;
import io.github.dabaihenhaochi.wayside.entity.ai.NpcBlockInteraction;
import io.github.dabaihenhaochi.wayside.entity.task.NpcTask;
import io.github.dabaihenhaochi.wayside.registry.ModEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

@GameTestHolder(Wayside.MODID)
@PrefixGameTestTemplate(false)
public final class ConstructionReviewGameTests {
    private static final BlockPos FEET = new BlockPos(5, 1, 5);
    private static final BlockPos DIRT_TARGET = new BlockPos(7, 3, 5);

    private static NpcEntity prepare(GameTestHelper helper, int cliffHeight) {
        for (int x = 0; x < 13; x++) for (int z = 0; z < 12; z++) {
            helper.setBlock(x, 0, z, Blocks.STONE);
            helper.setBlock(x, 6, z, Blocks.AIR);
            if (x >= 6) for (int y = 1; y <= cliffHeight; y++) helper.setBlock(x, y, z, Blocks.STONE);
        }
        NpcEntity npc = helper.spawn(ModEntities.NPC.get(), FEET);
        npc.getTaskRunner().cancel();
        npc.getInventory().clearContent();
        npc.setOnGround(true);
        return npc;
    }

    private static void runReservedTerrain(GameTestHelper helper, NpcEntity npc) {
        npc.getInventory().setItem(0, new ItemStack(Items.DIRT, 2)); // 一块用于地形，一块可垫脚。
        ConstructionExecutor executor = new ConstructionExecutor(true);
        BlockPos target = helper.absolutePos(DIRT_TARGET);
        helper.assertTrue(!NpcBlockInteraction.canPlaceAt(npc, target, Blocks.DIRT.defaultBlockState(), npc.position()),
                "Reserve fixture must require recovery rather than normal placement");
        npc.getTaskRunner().setTask(new NpcTask() {
            @Override public String typeId() { return "wayside:test_review_terrain"; }
            @Override public String stageName() { return executor.stageName(); }
            @Override public ActionResult tick(NpcEntity actor) {
                return executor.tick(actor, () -> new ConstructionPlanner.Plan(
                        actor.level().getBlockState(target).is(Blocks.DIRT) ? List.of() : List.of(
                                new ConstructionOperation(target, Blocks.DIRT.defaultBlockState(), ConstructionOperation.Action.PLACE)),
                        0, 0), pos -> !pos.equals(target));
            }
            @Override public void pause(NpcEntity actor) { executor.pause(actor); }
        });
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void restoredThenLostSightStillAllowsImmediateFallback(GameTestHelper helper) {
        NpcEntity npc = prepare(helper, 0);
        npc.setNoAi(true);
        for (int x = 4; x <= 6; x++) for (int z = 4; z <= 6; z++) for (int y = 1; y <= 3; y++) {
            if (x != 5 || z != 5 || y == 3) helper.setBlock(x, y, z, Blocks.STONE);
        }
        BlockPos target = helper.absolutePos(new BlockPos(10, 1, 5));
        npc.getInventory().setItem(0, new ItemStack(Items.OAK_PLANKS, 1));
        var plan = new ConstructionPlanner.Plan(List.of(new ConstructionOperation(
                target, Blocks.OAK_PLANKS.defaultBlockState(), ConstructionOperation.Action.PLACE)), 0, 0);
        ConstructionExecutor executor = new ConstructionExecutor(false);
        executor.tick(npc, () -> plan, pos -> true);
        helper.assertTrue(executor.stageName().equals("MOVING"), "Fixture must begin with a fallback approach");
        // 模拟移动后视线恢复，随后实际放置前视线再次丢失。
        npc.setPos(Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(9, 1, 5))));
        npc.setOnGround(true);
        executor.tick(npc, () -> plan, pos -> true);
        helper.assertTrue(executor.stageName().equals("PLACING"), "Approach must switch to normal placement");
        npc.setPos(Vec3.atBottomCenterOf(helper.absolutePos(FEET)));
        npc.setOnGround(true);
        executor.tick(npc, () -> plan, pos -> true);
        executor.tick(npc, () -> plan, pos -> true);
        helper.assertTrue(helper.getLevel().getBlockState(target).is(Blocks.OAK_PLANKS),
                "Lost sight after recovering normal placement must immediately retry through fallback");
        helper.assertTrue(npc.getInventory().countItem(Items.OAK_PLANKS) == 0, "Fallback must charge exactly once");
        executor.pause(npc);
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 120)
    public static void removingSpareDirtDuringJumpCannotConsumeTerrainReserve(GameTestHelper helper) {
        NpcEntity npc = prepare(helper, 2);
        runReservedTerrain(helper, npc);
        boolean[] removed = {false};
        helper.onEachTick(() -> {
            if (!removed[0] && npc.getY() > helper.absolutePos(FEET).getY() + 0.1) {
                npc.getInventory().setItem(0, new ItemStack(Items.DIRT, 1));
                removed[0] = true;
            }
        });
        helper.runAtTickTime(90, () -> {
            helper.assertTrue(removed[0], "Fixture must remove spare dirt after the jump starts");
            helper.assertBlockPresent(Blocks.AIR, FEET);
            helper.assertTrue(npc.getInventory().countItem(Items.DIRT) == 1,
                    "Access work must leave the remaining reserved terrain dirt intact");
            helper.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 120)
    public static void placementEventCannotSpendReservedTerrainDirt(GameTestHelper helper) {
        NpcEntity npc = prepare(helper, 2);
        boolean[] eventRan = {false};
        Consumer<BlockEvent.EntityPlaceEvent> listener = event -> {
            if (event.getEntity() == npc && event.getPos().equals(helper.absolutePos(FEET))) {
                eventRan[0] = true;
                npc.getInventory().setItem(0, new ItemStack(Items.DIRT, 1));
            }
        };
        NeoForge.EVENT_BUS.addListener(listener);
        runReservedTerrain(helper, npc);
        helper.runAtTickTime(90, () -> {
            NeoForge.EVENT_BUS.unregister(listener);
            helper.assertTrue(eventRan[0], "Fixture must change inventory inside the placement event");
            helper.assertBlockPresent(Blocks.AIR, FEET);
            helper.assertTrue(npc.getInventory().countItem(Items.DIRT) == 1,
                    "Commit must recheck the reserve and roll back without charging reserved dirt");
            helper.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 120)
    public static void reservationAddedInsidePlacementEventPreventsCommit(GameTestHelper helper) {
        NpcEntity npc = prepare(helper, 2);
        UUID[] reservation = {null};
        Consumer<BlockEvent.EntityPlaceEvent> listener = event -> {
            if (reservation[0] == null && event.getEntity() == npc && event.getPos().equals(helper.absolutePos(FEET))) {
                reservation[0] = UUID.randomUUID();
                var building = new BuildingInstance(reservation[0], ResourceLocation.fromNamespaceAndPath("wayside", "review_reservation"),
                        helper.absolutePos(FEET), Rotation.NONE);
                BuildingSavedData.get(helper.getLevel()).add(building, new BoundingBox(helper.absolutePos(FEET)));
            }
        };
        NeoForge.EVENT_BUS.addListener(listener);
        runReservedTerrain(helper, npc);
        helper.runAtTickTime(90, () -> {
            NeoForge.EVENT_BUS.unregister(listener);
            try {
                helper.assertTrue(reservation[0] != null, "Fixture must reserve space during the placement event");
                helper.assertBlockPresent(Blocks.AIR, FEET);
                helper.assertTrue(npc.getInventory().countItem(Items.DIRT) == 2,
                        "A new reservation must prevent commit without charging access dirt");
            } finally {
                if (reservation[0] != null) new BuildingManager(helper.getLevel()).remove(reservation[0]);
            }
            helper.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void stationSearchBudgetDefersWithoutLosingNextCandidate(GameTestHelper helper) {
        NpcEntity npc = prepare(helper, 4);
        BlockPos blocked = helper.absolutePos(new BlockPos(7, 5, 5));
        BlockPos reachable = helper.absolutePos(new BlockPos(1, 1, 5));
        helper.getLevel().setBlockAndUpdate(blocked, Blocks.STONE.defaultBlockState());
        helper.getLevel().setBlockAndUpdate(reachable, Blocks.STONE.defaultBlockState());
        var selector = new ConstructionWorkSelector(false);
        selector.prepare(List.of(new ConstructionOperation(blocked, Blocks.AIR.defaultBlockState(), ConstructionOperation.Action.REMOVE),
                new ConstructionOperation(reachable, Blocks.AIR.defaultBlockState(), ConstructionOperation.Action.REMOVE)), npc);
        helper.assertTrue(selector.next(npc, true) == null && !selector.exhausted(),
                "Execution should stop after one expensive station search and keep the next candidate");
        var next = selector.next(npc, true);
        helper.assertTrue(next != null && next.operation().pos().equals(reachable),
                "The next execution tick must resume and find the reachable candidate");
        helper.succeed();
    }
}
