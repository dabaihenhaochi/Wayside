package io.github.dabaihenhaochi.wayside.gametest;

import io.github.dabaihenhaochi.wayside.Wayside;
import io.github.dabaihenhaochi.wayside.building.BuildingInstance;
import io.github.dabaihenhaochi.wayside.entity.NpcEntity;
import io.github.dabaihenhaochi.wayside.entity.action.*;
import io.github.dabaihenhaochi.wayside.entity.ai.NpcMovementSafety;
import io.github.dabaihenhaochi.wayside.entity.task.NpcTask;
import io.github.dabaihenhaochi.wayside.entity.task.NpcTaskRunner;
import io.github.dabaihenhaochi.wayside.entity.task.build.BuildStructureTask;
import io.github.dabaihenhaochi.wayside.registry.ModEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;
import java.util.function.Consumer;

@GameTestHolder(Wayside.MODID)
@PrefixGameTestTemplate(false)
public final class PillarUpGameTests {
    private static NpcEntity prepare(GameTestHelper helper, BlockPos feet) {
        for (int x = 0; x < 13; x++) for (int z = 0; z < 12; z++) helper.setBlock(x, 0, z, Blocks.STONE);
        NpcEntity npc = helper.spawn(ModEntities.NPC.get(), feet);
        npc.getTaskRunner().cancel();
        npc.getInventory().clearContent();
        return npc;
    }

    private static void runAction(NpcEntity npc, NpcAction action) {
        npc.getTaskRunner().setTask(new NpcTask() {
            boolean started;
            @Override public String typeId() { return "wayside:test_action"; }
            @Override public String stageName() { return "TESTING"; }
            @Override public ActionResult tick(NpcEntity actor) {
                if (!started && !actor.onGround()) return ActionResult.running();
                started = true;
                return action.tick(actor);
            }
            @Override public void pause(NpcEntity actor) { action.stop(actor); }
        });
    }

    @GameTest(template = "empty", timeoutTicks = 160)
    public static void jumpsPlacesOneDirtAndLandsOneBlockHigher(GameTestHelper helper) {
        BlockPos feet = new BlockPos(4, 1, 5);
        NpcEntity npc = prepare(helper, feet);
        npc.getInventory().setItem(0, new ItemStack(Items.DIRT, 2));
        PillarUpAction action = new PillarUpAction();
        runAction(npc, action);
        helper.succeedWhen(() -> {
            helper.assertTrue(npc.getTaskRunner().state() == NpcTaskRunner.State.COMPLETED,
                    "Pillar action should complete: " + npc.getTaskRunner().description());
            helper.assertBlockPresent(Blocks.DIRT, feet);
            helper.assertTrue(npc.onGround() && Math.abs(npc.getY() - helper.absolutePos(feet.above()).getY()) < 0.05,
                    "Success requires a physical landing on the new block");
            helper.assertTrue(npc.getInventory().countItem(Items.DIRT) == 1 && action.tick(npc).isSuccess()
                    && npc.getInventory().countItem(Items.DIRT) == 1, "Completion must not charge a second time");
        });
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void missingDirtAndLowCeilingDoNotChangeWorld(GameTestHelper helper) {
        NpcEntity npc = prepare(helper, new BlockPos(4, 1, 5));
        npc.setOnGround(true);
        helper.assertTrue(new PillarUpAction().tick(npc).failure() == ActionResult.Failure.MISSING_INGREDIENT,
                "Missing dirt must fail before jumping");
        npc.getInventory().setItem(0, new ItemStack(Items.DIRT, 1));
        helper.setBlock(4, 3, 5, Blocks.STONE);
        helper.assertTrue(new PillarUpAction().tick(npc).failure() == ActionResult.Failure.NO_SPACE,
                "A low ceiling must reject the jump");
        helper.assertTrue(npc.getInventory().countItem(Items.DIRT) == 1, "Rejected preparation must preserve dirt");
        helper.assertBlockPresent(Blocks.AIR, new BlockPos(4, 1, 5));
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 160)
    public static void enoughLandingSpaceDoesNotRequireTheFullJumpApex(GameTestHelper helper) {
        NpcEntity npc = prepare(helper, new BlockPos(4, 1, 5));
        npc.getInventory().setItem(0, new ItemStack(Items.DIRT, 1));
        helper.setBlock(4, 4, 5, Blocks.STONE);
        runAction(npc, new PillarUpAction());
        helper.succeedWhen(() -> {
            helper.assertTrue(npc.getTaskRunner().state() == NpcTaskRunner.State.COMPLETED,
                    "A ceiling that leaves enough room above the placed block must allow recovery");
            helper.assertBlockPresent(Blocks.DIRT, new BlockPos(4, 1, 5));
        });
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void cancelingDuringTheJumpCannotPlaceLater(GameTestHelper helper) {
        NpcEntity npc = prepare(helper, new BlockPos(4, 1, 5));
        npc.getInventory().setItem(0, new ItemStack(Items.DIRT, 1));
        runAction(npc, new PillarUpAction());
        helper.onEachTick(() -> {
            if (npc.getY() > helper.absolutePos(new BlockPos(4, 1, 5)).getY() + 0.1
                    && helper.getBlockState(new BlockPos(4, 1, 5)).isAir()) npc.getTaskRunner().cancel();
        });
        helper.runAtTickTime(60, () -> {
            helper.assertTrue(npc.getTaskRunner().state() == NpcTaskRunner.State.CANCELED,
                    "The test must interrupt a physical jump before placement");
            helper.assertTrue(npc.getInventory().countItem(Items.DIRT) == 1, "Interrupted jump cannot charge dirt");
            helper.assertBlockPresent(Blocks.AIR, new BlockPos(4, 1, 5));
            helper.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void canceledUnderfootPlacementKeepsDirtAndAir(GameTestHelper helper) {
        NpcEntity npc = prepare(helper, new BlockPos(4, 1, 5));
        npc.getInventory().setItem(0, new ItemStack(Items.DIRT, 1));
        BlockPos target = helper.absolutePos(new BlockPos(4, 1, 5));
        Consumer<BlockEvent.EntityPlaceEvent> listener = event -> {
            if (event.getEntity() == npc && event.getPos().equals(target)) event.setCanceled(true);
        };
        NeoForge.EVENT_BUS.addListener(listener);
        runAction(npc, new PillarUpAction());
        helper.runAtTickTime(60, () -> NeoForge.EVENT_BUS.unregister(listener));
        helper.runAtTickTime(61, () -> {
            helper.assertTrue(npc.getTaskRunner().lastResult().failure() == ActionResult.Failure.FORBIDDEN,
                    "Underfoot placement must respect block-place cancellation");
            helper.assertTrue(npc.getInventory().countItem(Items.DIRT) == 1 && helper.getLevel().getBlockState(target).isAir(),
                    "Canceled placement must restore the temporary block and preserve its material");
            helper.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void navigationRefusesADeepDrop(GameTestHelper helper) {
        NpcEntity npc = prepare(helper, new BlockPos(3, 5, 5));
        for (int x = 0; x <= 4; x++) for (int z = 0; z < 12; z++) helper.setBlock(x, 4, z, Blocks.STONE);
        runAction(npc, new MoveToAction(helper.absolutePos(new BlockPos(8, 1, 5)), 1));
        helper.runAtTickTime(60, () -> {
            helper.assertTrue(npc.getTaskRunner().state() == NpcTaskRunner.State.FAILED
                            && npc.getTaskRunner().lastResult().failure() == ActionResult.Failure.UNREACHABLE,
                    "A four-block drop must be refused instead of walked down");
            helper.assertTrue(npc.getY() >= helper.absolutePos(new BlockPos(3, 5, 5)).getY() - 0.05,
                    "NPC must remain on the high platform");
            helper.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void finalPositionCorrectionRejectsAnUnsupportedTile(GameTestHelper helper) {
        NpcEntity npc = prepare(helper, new BlockPos(4, 1, 5));
        npc.setOnGround(true);
        helper.setBlock(5, 0, 5, Blocks.AIR);
        helper.assertTrue(!NpcMovementSafety.canWalkDirectly(npc,
                Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(5, 1, 5)))),
                "Movement correction must inspect footing, not just empty body space");
        helper.succeed();
    }

    private static BuildingInstance cliffTarget(GameTestHelper helper, String name, boolean dirt) {
        for (int x = 6; x < 13; x++) for (int z = 0; z < 12; z++)
            for (int y = 1; y <= 4; y++) helper.setBlock(x, y, z, Blocks.STONE);
        BlockPos prototype = new BlockPos(1, 9, 1);
        helper.setBlock(prototype, dirt ? Blocks.DIRT : Blocks.OAK_PLANKS);
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath("wayside", "pillar_test_" + name);
        helper.getLevel().getStructureManager().getOrCreate(id).fillFromWorld(helper.getLevel(), helper.absolutePos(prototype),
                new Vec3i(1, 1, 1), false, Blocks.STRUCTURE_VOID);
        return new BuildingInstance(UUID.randomUUID(), id, helper.absolutePos(new BlockPos(7, 5, 5)), Rotation.NONE);
    }

    @GameTest(template = "empty", timeoutTicks = 900)
    public static void constructionFallsBackWithoutRaisingWhenNormalStationsAreUnreachable(GameTestHelper helper) {
        NpcEntity npc = prepare(helper, new BlockPos(5, 1, 5));
        var building = cliffTarget(helper, "recovery", false);
        npc.getInventory().setItem(0, new ItemStack(Items.OAK_PLANKS, 1));
        npc.getInventory().setItem(1, new ItemStack(Items.DIRT, 8));
        npc.getTaskRunner().setTask(new BuildStructureTask(building));
        helper.succeedWhen(() -> {
            helper.assertTrue(npc.getTaskRunner().state() == NpcTaskRunner.State.COMPLETED,
                    "Blocked construction should use remote placement: " + npc.getTaskRunner().description() + " at " + npc.position());
            helper.assertBlockPresent(Blocks.OAK_PLANKS, new BlockPos(7, 5, 5));
            int dirt = 0;
            for (int y = 1; y <= 8; y++) if (helper.getBlockState(new BlockPos(5, y, 5)).is(Blocks.DIRT)) dirt++;
            helper.assertTrue(dirt == 0 && npc.getInventory().countItem(Items.DIRT) == 8,
                    "Placement fallback must not build an unnecessary dirt column");
        });
    }

    @GameTest(template = "empty", timeoutTicks = 1400)
    public static void constructionKeepsDirtReservedForItsBlueprint(GameTestHelper helper) {
        NpcEntity npc = prepare(helper, new BlockPos(5, 1, 5));
        var building = cliffTarget(helper, "reserved", true);
        npc.getInventory().setItem(0, new ItemStack(Items.DIRT, 1));
        npc.getTaskRunner().setTask(new BuildStructureTask(building));
        helper.succeedWhen(() -> {
            helper.assertTrue(npc.getTaskRunner().state() == NpcTaskRunner.State.COMPLETED, "Fallback must complete unreachable construction");
            helper.assertTrue(npc.getInventory().countItem(Items.DIRT) == 0, "Only the blueprint block must consume dirt");
            helper.assertBlockPresent(Blocks.DIRT, new BlockPos(7, 5, 5));
            helper.assertBlockPresent(Blocks.AIR, new BlockPos(5, 1, 5));
        });
    }

    @GameTest(template = "empty", timeoutTicks = 1400)
    public static void unsupportedTargetDoesNotWasteAccessDirt(GameTestHelper helper) {
        NpcEntity npc = prepare(helper, new BlockPos(5, 1, 5));
        helper.setBlock(1, 2, 1, Blocks.STONE);
        helper.setBlock(1, 3, 1, Blocks.SAND);
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath("wayside", "pillar_unsupported_target");
        helper.getLevel().getStructureManager().getOrCreate(id).fillFromWorld(helper.getLevel(),
                helper.absolutePos(new BlockPos(1, 3, 1)), new Vec3i(1, 1, 1), false, Blocks.STRUCTURE_VOID);
        var building = new BuildingInstance(UUID.randomUUID(), id, helper.absolutePos(new BlockPos(7, 3, 5)), Rotation.NONE);
        npc.getInventory().setItem(0, new ItemStack(Items.SAND, 1));
        npc.getInventory().setItem(1, new ItemStack(Items.DIRT, 8));
        npc.getTaskRunner().setTask(new BuildStructureTask(building));
        helper.succeedWhen(() -> {
            helper.assertTrue(npc.getTaskRunner().state() == NpcTaskRunner.State.FAILED, "Unsupported sand must remain unbuildable");
            helper.assertTrue(npc.getInventory().countItem(Items.DIRT) == 8, "Height changes cannot fix missing target support");
            helper.assertBlockPresent(Blocks.AIR, new BlockPos(5, 1, 5));
        });
    }

    @GameTest(template = "empty", timeoutTicks = 1500)
    public static void legacyRiseLimitDoesNotPreventPlacementFallbackAfterReload(GameTestHelper helper) {
        NpcEntity npc = prepare(helper, new BlockPos(5, 1, 5));
        var building = cliffTarget(helper, "resume_limit", false);
        npc.getInventory().setItem(0, new ItemStack(Items.OAK_PLANKS, 1));
        npc.getInventory().setItem(1, new ItemStack(Items.DIRT, 8));
        var task = new BuildStructureTask(building);
        var data = task.save();
        data.getCompound("Execution").putInt("ConsecutiveRise", 8);
        task.load(data);
        helper.assertTrue(task.save().getCompound("Execution").getInt("ConsecutiveRise") == 8,
                "Legacy access progress must survive loading");
        npc.getTaskRunner().setTask(task);
        helper.succeedWhen(() -> {
            helper.assertTrue(npc.getTaskRunner().state() == NpcTaskRunner.State.COMPLETED,
                    "An old rise limit must not block the new placement fallback");
            helper.assertTrue(npc.getInventory().countItem(Items.DIRT) == 8,
                    "Reloaded construction should not require additional access dirt");
        });
    }
}
