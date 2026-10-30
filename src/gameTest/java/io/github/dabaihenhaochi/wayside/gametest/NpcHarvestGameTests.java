package io.github.dabaihenhaochi.wayside.gametest;

import io.github.dabaihenhaochi.wayside.Wayside;
import net.minecraft.tags.ItemTags;
import io.github.dabaihenhaochi.wayside.entity.inventory.InventoryOps;
import io.github.dabaihenhaochi.wayside.entity.NpcEntity;
import io.github.dabaihenhaochi.wayside.entity.ai.NpcBlockInteraction;
import io.github.dabaihenhaochi.wayside.entity.task.NpcTask;
import io.github.dabaihenhaochi.wayside.entity.task.resource.GatherResourceTask;
import io.github.dabaihenhaochi.wayside.registry.ModEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(Wayside.MODID)
@PrefixGameTestTemplate(false)
public class NpcHarvestGameTests {

    private static final BlockPos LOG = new BlockPos(5, 1, 5);

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void waitsThenDropsExactlyOneLog(GameTestHelper helper) {
        prepareFloor(helper);
        helper.setBlock(LOG, Blocks.OAK_LOG);
        NpcEntity npc = spawnHarvester(helper, new BlockPos(4, 1, 5));

        helper.runAtTickTime(30, () -> helper.assertBlockPresent(Blocks.OAK_LOG, LOG));
        helper.runAtTickTime(80, () -> {
            helper.assertBlockPresent(Blocks.AIR, LOG);
            assertTotalLogs(helper, LOG, 5.0, 1);
            helper.assertTrue(!(npc.getTaskRunner().activeBlockTarget() != null) && !(npc.getTaskRunner().activeMoveTarget() != null), "Completed targets must be cleared");
            helper.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 240)
    public static void findsWalksAndHarvests(GameTestHelper helper) {
        prepareFloor(helper);
        BlockPos distantLog = new BlockPos(8, 1, 5);
        helper.setBlock(distantLog, Blocks.OAK_LOG);
        spawnHarvester(helper, new BlockPos(2, 1, 5));

        helper.succeedWhen(() -> {
            helper.assertBlockPresent(Blocks.AIR, distantLog);
            assertTotalLogs(helper, distantLog, 5.0, 1);
        });
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void removedTargetProducesNoDrop(GameTestHelper helper) {
        prepareFloor(helper);
        helper.setBlock(LOG, Blocks.OAK_LOG);
        NpcEntity npc = spawnHarvester(helper, new BlockPos(4, 1, 5));

        helper.runAtTickTime(20, () -> {
            helper.assertTrue((npc.getTaskRunner().activeBlockTarget() != null) && !(npc.getTaskRunner().activeMoveTarget() != null), "NPC must have started working before target removal");
            helper.setBlock(LOG, Blocks.AIR);
        });
        helper.runAtTickTime(80, () -> {
            helper.assertItemEntityNotPresent(Items.OAK_LOG);
            helper.assertTrue(!(npc.getTaskRunner().activeBlockTarget() != null) && !(npc.getTaskRunner().activeMoveTarget() != null), "Removed target must be abandoned");
            helper.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 700)
    public static void thinWallPreventsHarvestAndEventuallyCoolsDown(GameTestHelper helper) {
        prepareFloor(helper);
        helper.setBlock(LOG, Blocks.OAK_LOG);
        helper.setBlock(4, 1, 5, Blocks.IRON_BARS);
        helper.setBlock(4, 2, 5, Blocks.IRON_BARS);
        GatherResourceTask gathering = logGatherer();
        NpcEntity npc = spawnHarvester(helper, new Vec3(4.1, 1.0, 5.5), gathering);
        npc.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(0.0);

        // 目标在操作距离内，但细栏杆挡住了实际操作视线。
        helper.assertTrue(!NpcBlockInteraction.canWorkAt(npc, helper.absolutePos(LOG), npc.position()), "Wall must block the interaction ray");
        helper.runAtTickTime(660, () -> {
            helper.assertBlockPresent(Blocks.OAK_LOG, LOG);
            helper.assertItemEntityNotPresent(Items.OAK_LOG);
            helper.assertTrue(gathering.isBlockOnCooldown(helper.absolutePos(LOG), helper.getLevel().getGameTime()), "Unreachable target must cool down");
            helper.assertTrue(!(npc.getTaskRunner().activeBlockTarget() != null) && !(npc.getTaskRunner().activeMoveTarget() != null), "Timed-out targets must be cleared");
            helper.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void newObstructionInterruptsWork(GameTestHelper helper) {
        prepareFloor(helper);
        helper.setBlock(LOG, Blocks.OAK_LOG);
        GatherResourceTask gathering = logGatherer();
        NpcEntity npc = spawnHarvester(helper, new Vec3(4.1, 1.0, 5.5), gathering);
        npc.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(0.0);

        helper.runAtTickTime(20, () -> {
            helper.assertTrue((npc.getTaskRunner().activeBlockTarget() != null) && !(npc.getTaskRunner().activeMoveTarget() != null), "NPC must be working before obstruction");
            helper.setBlock(4, 1, 5, Blocks.IRON_BARS);
            helper.setBlock(4, 2, 5, Blocks.IRON_BARS);
        });
        helper.runAtTickTime(80, () -> {
            helper.assertBlockPresent(Blocks.OAK_LOG, LOG);
            helper.assertItemEntityNotPresent(Items.OAK_LOG);
            helper.assertTrue((npc.getTaskRunner().activeMoveTarget() != null)
                            || gathering.isBlockOnCooldown(helper.absolutePos(LOG), helper.getLevel().getGameTime()),
                    "Interrupted work must replan movement or abandon an unreachable target");
            helper.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void manualMoveCancelsHarvest(GameTestHelper helper) {
        prepareFloor(helper);
        helper.setBlock(LOG, Blocks.OAK_LOG);
        NpcEntity npc = spawnHarvester(helper, new BlockPos(4, 1, 5));

        helper.runAtTickTime(20, () -> {
            helper.assertTrue((npc.getTaskRunner().activeBlockTarget() != null) && !(npc.getTaskRunner().activeMoveTarget() != null), "NPC must be working before the manual command");

            npc.getTaskRunner().temporarilyRun(new io.github.dabaihenhaochi.wayside.entity.task.VisitPositionTask(
                    helper.absolutePos(new BlockPos(1, 1, 1))));
        });
        helper.runAtTickTime(45, () -> {
            helper.assertBlockPresent(Blocks.OAK_LOG, LOG);
            helper.assertItemEntityNotPresent(Items.OAK_LOG);
            helper.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 280)
    public static void harvestsTwoLogsInSequence(GameTestHelper helper) {
        prepareFloor(helper);
        BlockPos secondLog = LOG.east(2);
        helper.setBlock(LOG, Blocks.OAK_LOG);
        helper.setBlock(secondLog, Blocks.OAK_LOG);
        spawnHarvester(helper, new BlockPos(4, 1, 5));

        helper.succeedWhen(() -> {
            helper.assertBlockPresent(Blocks.AIR, LOG);
            helper.assertBlockPresent(Blocks.AIR, secondLog);
            assertTotalLogs(helper, LOG, 6.0, 2);
        });
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void harvestsAboveItsHead(GameTestHelper helper) {
        prepareFloor(helper);
        BlockPos overheadLog = LOG.above(2);
        helper.setBlock(overheadLog, Blocks.OAK_LOG);
        spawnHarvester(helper, new BlockPos(4, 1, 5));

        helper.runAtTickTime(80, () -> {
            helper.assertBlockPresent(Blocks.AIR, overheadLog);
            assertTotalLogs(helper, overheadLog, 5.0, 1);
            helper.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 240)
    public static void walksToGroundPositionForElevatedLog(GameTestHelper helper) {
        prepareFloor(helper);
        BlockPos elevatedLog = new BlockPos(8, 3, 5);
        helper.setBlock(elevatedLog, Blocks.OAK_LOG);
        NpcEntity npc = spawnHarvester(helper, new BlockPos(2, 1, 5));
        boolean[] sawSeparateWorkPosition = {false};

        helper.runAtTickTime(120, () -> Wayside.LOGGER.debug(
                "Elevated-log test: npc={}, movement={}, resource={}, onGround={}, pathDone={}, canWork={}",
                npc.position(), npc.getTaskRunner().activeMoveTarget(), npc.getTaskRunner().activeBlockTarget(), npc.onGround(),
                npc.getNavigation().isDone(), NpcBlockInteraction.canWorkAt(npc, helper.absolutePos(elevatedLog), npc.position())));
        helper.onEachTick(() -> {
            if ((npc.getTaskRunner().activeMoveTarget() != null) && (npc.getTaskRunner().activeBlockTarget() != null)) {
                helper.assertTrue(!npc.getTaskRunner().activeMoveTarget().equals(npc.getTaskRunner().activeBlockTarget()),
                        "Movement must target a standing position, not the elevated resource");
                helper.assertTrue(npc.getTaskRunner().activeMoveTarget().getY() == helper.absolutePos(new BlockPos(0, 1, 0)).getY(),
                        "Work position must stay on the ground");
                sawSeparateWorkPosition[0] = true;
            }
        });
        helper.succeedWhen(() -> {
            helper.assertBlockPresent(Blocks.AIR, elevatedLog);
            assertTotalLogs(helper, elevatedLog, 5.0, 1);
            helper.assertTrue(sawSeparateWorkPosition[0], "NPC must first walk to a distinct work position");
        });
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void harvestsLogBelowItsFeet(GameTestHelper helper) {
        prepareFloor(helper);
        BlockPos lowerLog = LOG.below();
        helper.setBlock(lowerLog, Blocks.OAK_LOG);
        spawnHarvester(helper, new BlockPos(4, 1, 5));

        helper.runAtTickTime(80, () -> {
            helper.assertBlockPresent(Blocks.AIR, lowerLog);
            assertTotalLogs(helper, lowerLog, 5.0, 1);
            helper.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void stepsOffSupportingLogBeforeHarvest(GameTestHelper helper) {
        prepareFloor(helper);
        BlockPos lowerLog = LOG.below();
        helper.setBlock(lowerLog, Blocks.OAK_LOG);
        NpcEntity npc = spawnHarvester(helper, LOG);
        double floorY = npc.getY();
        helper.assertTrue(!NpcBlockInteraction.canWorkAt(npc, helper.absolutePos(lowerLog), npc.position()),
                "NPC must not harvest its current support block");

        helper.onEachTick(() -> helper.assertTrue(npc.getY() >= floorY - 0.05,
                "NPC must not fall through its harvested support block"));
        helper.succeedWhen(() -> {
            helper.assertBlockPresent(Blocks.AIR, lowerLog);
            assertTotalLogs(helper, lowerLog, 5.0, 1);
            helper.assertTrue(!npc.blockPosition().below().equals(helper.absolutePos(lowerLog)),
                    "NPC must move aside before harvesting its support");
        });
    }

    @GameTest(template = "empty", timeoutTicks = 240)
    public static void avoidsUnsupportedAndCrampedWorkPositions(GameTestHelper helper) {
        prepareFloor(helper);
        BlockPos elevatedLog = LOG.above(2);
        helper.setBlock(elevatedLog, Blocks.OAK_LOG);
        helper.setBlock(4, 0, 5, Blocks.AIR);
        helper.setBlock(6, 2, 5, Blocks.STONE);
        NpcEntity npc = spawnHarvester(helper, new BlockPos(2, 1, 5));

        helper.assertTrue(!NpcBlockInteraction.canWorkAt(npc, helper.absolutePos(elevatedLog),
                helper.absoluteVec(new Vec3(4.5, 1.0, 5.5))), "Unsupported work position must be rejected");
        helper.assertTrue(!NpcBlockInteraction.canWorkAt(npc, helper.absolutePos(elevatedLog),
                helper.absoluteVec(new Vec3(6.5, 1.0, 5.5))), "Cramped work position must be rejected");
        double floorY = npc.getY();
        helper.onEachTick(() -> helper.assertTrue(npc.getY() >= floorY - 0.05,
                "NPC must use a supported route and work position"));
        helper.succeedWhen(() -> {
            helper.assertBlockPresent(Blocks.AIR, elevatedLog);
            assertTotalLogs(helper, elevatedLog, 5.0, 1);
        });
    }

    @GameTest(template = "empty", timeoutTicks = 120)
    public static void rejectsUnreachableWorkPositions(GameTestHelper helper) {
        prepareFloor(helper);
        BlockPos enclosedLog = new BlockPos(7, 3, 5);
        helper.setBlock(enclosedLog, Blocks.OAK_LOG);
        for (int x = 3; x <= 11; x++) {
            for (int z = 1; z <= 10; z++) {
                if (x == 3 || x == 11 || z == 1 || z == 10) {
                    for (int y = 1; y <= 4; y++) {
                        helper.setBlock(x, y, z, Blocks.STONE);
                    }
                }
            }
        }
        GatherResourceTask gathering = logGatherer();
        NpcEntity npc = spawnHarvester(helper, Vec3.atBottomCenterOf(new BlockPos(2, 1, 5)), gathering);

        helper.runAtTickTime(80, () -> {
            helper.assertBlockPresent(Blocks.OAK_LOG, enclosedLog);
            helper.assertItemEntityNotPresent(Items.OAK_LOG);
            helper.assertTrue(gathering.isBlockOnCooldown(helper.absolutePos(enclosedLog), helper.getLevel().getGameTime()),
                    "A resource with no reachable standing position must cool down");
            helper.assertTrue(!(npc.getTaskRunner().activeBlockTarget() != null) && !(npc.getTaskRunner().activeMoveTarget() != null),
                    "Unreachable work positions must not leave stale movement targets");
            helper.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void removedElevatedTargetClearsItsWorkPosition(GameTestHelper helper) {
        prepareFloor(helper);
        BlockPos elevatedLog = new BlockPos(8, 3, 5);
        helper.setBlock(elevatedLog, Blocks.OAK_LOG);
        NpcEntity npc = spawnHarvester(helper, new BlockPos(2, 1, 5));

        helper.runAtTickTime(10, () -> {
            helper.assertTrue((npc.getTaskRunner().activeMoveTarget() != null) && (npc.getTaskRunner().activeBlockTarget() != null), "NPC must still be approaching");
            helper.assertTrue(!npc.getTaskRunner().activeMoveTarget().equals(npc.getTaskRunner().activeBlockTarget()), "Work position must differ from resource");
            helper.setBlock(elevatedLog, Blocks.AIR);
        });
        helper.runAtTickTime(30, () -> {
            helper.assertTrue(!(npc.getTaskRunner().activeBlockTarget() != null) && !(npc.getTaskRunner().activeMoveTarget() != null),
                    "Removing a resource must also clear its distinct work position");
            helper.assertItemEntityNotPresent(Items.OAK_LOG);
            helper.succeed();
        });
    }

    @GameTest(template = "empty", batch = "mobGriefingDisabled", timeoutTicks = 100)
    public static void respectsMobGriefingRule(GameTestHelper helper) {
        prepareFloor(helper);
        helper.setBlock(LOG, Blocks.OAK_LOG);
        // 独立批次避免改变同一世界里其它并行测试的规则。
        GameRules.BooleanValue rule = helper.getLevel().getGameRules().getRule(GameRules.RULE_MOBGRIEFING);
        boolean originalValue = rule.get();
        rule.set(false, helper.getLevel().getServer());
        spawnHarvester(helper, new BlockPos(4, 1, 5));

        helper.runAtTickTime(80, () -> {
            rule.set(originalValue, helper.getLevel().getServer());
            helper.assertBlockPresent(Blocks.OAK_LOG, LOG);
            helper.assertItemEntityNotPresent(Items.OAK_LOG);
            helper.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 120)
    public static void gathersNonWoodUsingTheSameFilterForPickupAndInventory(GameTestHelper helper) {
        prepareFloor(helper);
        NpcEntity npc = helper.spawn(ModEntities.NPC.get(), new BlockPos(2, 1, 2));
        npc.getInventory().clearContent();
        npc.getInventory().setItem(0, new net.minecraft.world.item.ItemStack(Items.OAK_LOG, 64));
        npc.getInventory().setItem(1, new net.minecraft.world.item.ItemStack(Items.DIRT, 1));
        npc.getTaskRunner().setTask(new GatherResourceTask(state -> false, stack -> stack.is(Items.DIRT), 2));
        Vec3 itemPosition = Vec3.atCenterOf(helper.absolutePos(new BlockPos(2, 1, 2)));
        var item = new net.minecraft.world.entity.item.ItemEntity(helper.getLevel(),
                itemPosition.x, itemPosition.y, itemPosition.z,
                new net.minecraft.world.item.ItemStack(Items.DIRT, 1));
        item.setDeltaMovement(Vec3.ZERO);
        item.setNoPickUpDelay();
        helper.getLevel().addFreshEntity(item);

        helper.succeedWhen(() -> {
            helper.assertTrue(npc.getTaskRunner().state()
                            == io.github.dabaihenhaochi.wayside.entity.task.NpcTaskRunner.State.COMPLETED,
                    "Non-wood gathering must complete after collecting its own target item");
            helper.assertTrue(InventoryOps.count(npc.getInventory(), Items.DIRT) == 2
                            && InventoryOps.count(npc.getInventory(), Items.OAK_LOG) == 64,
                    "Existing unrelated inventory cannot satisfy the dirt goal");
        });
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void ensuresMixedWoodPlanksWithoutExhaustingSpareLogs(GameTestHelper helper) {
        prepareFloor(helper);
        NpcEntity npc = helper.spawn(ModEntities.NPC.get(), new BlockPos(2, 1, 2));
        npc.getInventory().clearContent();
        npc.getInventory().setItem(0, new net.minecraft.world.item.ItemStack(Items.OAK_LOG, 1));
        npc.getInventory().setItem(1, new net.minecraft.world.item.ItemStack(Items.BIRCH_LOG, 3));
        npc.getInventory().setItem(2, new net.minecraft.world.item.ItemStack(Items.OAK_PLANKS, 3));
        npc.getInventory().setItem(3, new net.minecraft.world.item.ItemStack(Items.BIRCH_PLANKS, 2));
        npc.getTaskRunner().setTask(new io.github.dabaihenhaochi.wayside.entity.task.resource.EnsurePlanksTask(9));

        helper.succeedWhen(() -> {
            helper.assertTrue(npc.getTaskRunner().state()
                            == io.github.dabaihenhaochi.wayside.entity.task.NpcTaskRunner.State.COMPLETED,
                    "The tag-based plank goal must finish using stored logs");
            helper.assertTrue(InventoryOps.count(npc.getInventory(), ItemTags.PLANKS) == 9
                            && InventoryOps.count(npc.getInventory(), Items.BIRCH_LOG) == 3,
                    "Existing plank variants must count toward the goal and spare logs must remain");
        });
    }

    private static void prepareFloor(GameTestHelper helper) {
        for (int x = 0; x < 12; x++) {
            for (int z = 0; z < 12; z++) {
                helper.setBlock(x, 0, z, Blocks.STONE);
            }
        }
    }
    // v0.0.7 后会主动拾取，采集结果应同时统计地上掉落物和 NPC 背包。
    private static void assertTotalLogs(GameTestHelper helper, BlockPos center, double radius, int expected) {
        var area = new net.minecraft.world.phys.AABB(helper.absolutePos(center)).inflate(radius);
        int count = helper.getLevel().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, area)
                .stream().filter(entity -> entity.getItem().is(Items.OAK_LOG))
                .mapToInt(entity -> entity.getItem().getCount()).sum();
        count += helper.getLevel().getEntitiesOfClass(NpcEntity.class, area).stream()
                .mapToInt(actor -> InventoryOps.count(actor.getInventory(), ItemTags.LOGS)).sum();
        helper.assertTrue(count == expected, "Expected " + expected + " harvested logs, found " + count);
    }
    // 默认目标已改为自动建屋；采集回归测试显式选择采集任务。
    private static NpcEntity spawnHarvester(GameTestHelper helper, BlockPos pos) {
        NpcEntity npc = helper.spawn(ModEntities.NPC.get(), pos);
        npc.getInventory().clearContent();
        npc.getTaskRunner().setTask(new io.github.dabaihenhaochi.wayside.entity.task.resource.GatherAndCraftPlanksTask());
        return npc;
    }
    private static NpcEntity spawnHarvester(GameTestHelper helper, Vec3 pos) {
        return spawnHarvester(helper, pos, new io.github.dabaihenhaochi.wayside.entity.task.resource.GatherAndCraftPlanksTask());
    }
    private static NpcEntity spawnHarvester(GameTestHelper helper, Vec3 pos, NpcTask task) {
        NpcEntity npc = helper.spawn(ModEntities.NPC.get(), pos);
        npc.getInventory().clearContent();
        npc.getTaskRunner().setTask(task);
        return npc;
    }
    private static GatherResourceTask logGatherer() {
        return new GatherResourceTask(state -> state.is(net.minecraft.tags.BlockTags.LOGS),
                stack -> stack.is(ItemTags.LOGS), 16);
    }
}
