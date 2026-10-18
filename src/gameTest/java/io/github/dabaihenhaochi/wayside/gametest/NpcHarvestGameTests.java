package io.github.dabaihenhaochi.wayside.gametest;

import io.github.dabaihenhaochi.wayside.Wayside;
import io.github.dabaihenhaochi.wayside.entity.NpcEntity;
import io.github.dabaihenhaochi.wayside.entity.ai.NpcBlockInteraction;
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
        NpcEntity npc = helper.spawn(ModEntities.NPC.get(), new BlockPos(4, 1, 5));

        helper.runAtTickTime(30, () -> helper.assertBlockPresent(Blocks.OAK_LOG, LOG));
        helper.runAtTickTime(80, () -> {
            helper.assertBlockPresent(Blocks.AIR, LOG);
            helper.assertItemEntityCountIs(Items.OAK_LOG, LOG, 5.0, 1);
            helper.assertTrue(!npc.hasBlockTarget() && !npc.hasMoveTarget(), "Completed targets must be cleared");
            helper.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 240)
    public static void findsWalksAndHarvests(GameTestHelper helper) {
        prepareFloor(helper);
        BlockPos distantLog = new BlockPos(8, 1, 5);
        helper.setBlock(distantLog, Blocks.OAK_LOG);
        helper.spawn(ModEntities.NPC.get(), new BlockPos(2, 1, 5));

        helper.succeedWhen(() -> {
            helper.assertBlockPresent(Blocks.AIR, distantLog);
            helper.assertItemEntityCountIs(Items.OAK_LOG, distantLog, 5.0, 1);
        });
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void removedTargetProducesNoDrop(GameTestHelper helper) {
        prepareFloor(helper);
        helper.setBlock(LOG, Blocks.OAK_LOG);
        NpcEntity npc = helper.spawn(ModEntities.NPC.get(), new BlockPos(4, 1, 5));

        helper.runAtTickTime(20, () -> {
            helper.assertTrue(npc.hasBlockTarget() && !npc.hasMoveTarget(), "NPC must have started working before target removal");
            helper.setBlock(LOG, Blocks.AIR);
        });
        helper.runAtTickTime(80, () -> {
            helper.assertItemEntityNotPresent(Items.OAK_LOG);
            helper.assertTrue(!npc.hasBlockTarget() && !npc.hasMoveTarget(), "Removed target must be abandoned");
            helper.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 700)
    public static void thinWallPreventsHarvestAndEventuallyCoolsDown(GameTestHelper helper) {
        prepareFloor(helper);
        helper.setBlock(LOG, Blocks.OAK_LOG);
        helper.setBlock(4, 1, 5, Blocks.IRON_BARS);
        helper.setBlock(4, 2, 5, Blocks.IRON_BARS);
        NpcEntity npc = helper.spawn(ModEntities.NPC.get(), new Vec3(4.1, 1.0, 5.5));
        npc.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(0.0);

        // 目标在操作距离内，但细栏杆挡住了实际操作视线。
        helper.assertTrue(!npc.canReachBlock(helper.absolutePos(LOG)), "Wall must block the interaction ray");
        helper.runAtTickTime(660, () -> {
            helper.assertBlockPresent(Blocks.OAK_LOG, LOG);
            helper.assertItemEntityNotPresent(Items.OAK_LOG);
            helper.assertTrue(npc.isBlockTargetOnCooldown(helper.absolutePos(LOG)), "Unreachable target must cool down");
            helper.assertTrue(!npc.hasBlockTarget() && !npc.hasMoveTarget(), "Timed-out targets must be cleared");
            helper.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void newObstructionInterruptsWork(GameTestHelper helper) {
        prepareFloor(helper);
        helper.setBlock(LOG, Blocks.OAK_LOG);
        NpcEntity npc = helper.spawn(ModEntities.NPC.get(), new Vec3(4.1, 1.0, 5.5));
        npc.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(0.0);

        helper.runAtTickTime(20, () -> {
            helper.assertTrue(npc.hasBlockTarget() && !npc.hasMoveTarget(), "NPC must be working before obstruction");
            helper.setBlock(4, 1, 5, Blocks.IRON_BARS);
            helper.setBlock(4, 2, 5, Blocks.IRON_BARS);
        });
        helper.runAtTickTime(80, () -> {
            helper.assertBlockPresent(Blocks.OAK_LOG, LOG);
            helper.assertItemEntityNotPresent(Items.OAK_LOG);
            helper.assertTrue(npc.hasMoveTarget() || npc.isBlockTargetOnCooldown(helper.absolutePos(LOG)),
                    "Interrupted work must replan movement or abandon an unreachable target");
            helper.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void manualMoveCancelsHarvest(GameTestHelper helper) {
        prepareFloor(helper);
        helper.setBlock(LOG, Blocks.OAK_LOG);
        NpcEntity npc = helper.spawn(ModEntities.NPC.get(), new BlockPos(4, 1, 5));

        helper.runAtTickTime(20, () -> {
            helper.assertTrue(npc.hasBlockTarget() && !npc.hasMoveTarget(), "NPC must be working before the manual command");
            npc.clearBlockTarget();
            npc.setMoveTarget(helper.absolutePos(new BlockPos(1, 1, 1)));
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
        helper.spawn(ModEntities.NPC.get(), new BlockPos(4, 1, 5));

        helper.succeedWhen(() -> {
            helper.assertBlockPresent(Blocks.AIR, LOG);
            helper.assertBlockPresent(Blocks.AIR, secondLog);
            helper.assertItemEntityCountIs(Items.OAK_LOG, LOG, 6.0, 2);
        });
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void harvestsAboveItsHead(GameTestHelper helper) {
        prepareFloor(helper);
        BlockPos overheadLog = LOG.above(2);
        helper.setBlock(overheadLog, Blocks.OAK_LOG);
        helper.spawn(ModEntities.NPC.get(), new BlockPos(4, 1, 5));

        helper.runAtTickTime(80, () -> {
            helper.assertBlockPresent(Blocks.AIR, overheadLog);
            helper.assertItemEntityCountIs(Items.OAK_LOG, overheadLog, 5.0, 1);
            helper.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 240)
    public static void walksToGroundPositionForElevatedLog(GameTestHelper helper) {
        prepareFloor(helper);
        BlockPos elevatedLog = new BlockPos(8, 3, 5);
        helper.setBlock(elevatedLog, Blocks.OAK_LOG);
        NpcEntity npc = helper.spawn(ModEntities.NPC.get(), new BlockPos(2, 1, 5));
        boolean[] sawSeparateWorkPosition = {false};

        helper.runAtTickTime(120, () -> Wayside.LOGGER.debug(
                "Elevated-log test: npc={}, movement={}, resource={}, onGround={}, pathDone={}, canWork={}",
                npc.position(), npc.getMoveTarget(), npc.getBlockTarget(), npc.onGround(),
                npc.getNavigation().isDone(), npc.canReachBlock(helper.absolutePos(elevatedLog))));
        helper.onEachTick(() -> {
            if (npc.hasMoveTarget() && npc.hasBlockTarget()) {
                helper.assertTrue(!npc.getMoveTarget().equals(npc.getBlockTarget()),
                        "Movement must target a standing position, not the elevated resource");
                helper.assertTrue(npc.getMoveTarget().getY() == helper.absolutePos(new BlockPos(0, 1, 0)).getY(),
                        "Work position must stay on the ground");
                sawSeparateWorkPosition[0] = true;
            }
        });
        helper.succeedWhen(() -> {
            helper.assertBlockPresent(Blocks.AIR, elevatedLog);
            helper.assertItemEntityCountIs(Items.OAK_LOG, elevatedLog, 5.0, 1);
            helper.assertTrue(sawSeparateWorkPosition[0], "NPC must first walk to a distinct work position");
        });
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void harvestsLogBelowItsFeet(GameTestHelper helper) {
        prepareFloor(helper);
        BlockPos lowerLog = LOG.below();
        helper.setBlock(lowerLog, Blocks.OAK_LOG);
        helper.spawn(ModEntities.NPC.get(), new BlockPos(4, 1, 5));

        helper.runAtTickTime(80, () -> {
            helper.assertBlockPresent(Blocks.AIR, lowerLog);
            helper.assertItemEntityCountIs(Items.OAK_LOG, lowerLog, 5.0, 1);
            helper.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void stepsOffSupportingLogBeforeHarvest(GameTestHelper helper) {
        prepareFloor(helper);
        BlockPos lowerLog = LOG.below();
        helper.setBlock(lowerLog, Blocks.OAK_LOG);
        NpcEntity npc = helper.spawn(ModEntities.NPC.get(), LOG);
        double floorY = npc.getY();
        helper.assertTrue(!npc.canReachBlock(helper.absolutePos(lowerLog)),
                "NPC must not harvest its current support block");

        helper.onEachTick(() -> helper.assertTrue(npc.getY() >= floorY - 0.05,
                "NPC must not fall through its harvested support block"));
        helper.succeedWhen(() -> {
            helper.assertBlockPresent(Blocks.AIR, lowerLog);
            helper.assertItemEntityCountIs(Items.OAK_LOG, lowerLog, 5.0, 1);
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
        NpcEntity npc = helper.spawn(ModEntities.NPC.get(), new BlockPos(2, 1, 5));

        helper.assertTrue(!NpcBlockInteraction.canWorkAt(npc, helper.absolutePos(elevatedLog),
                helper.absoluteVec(new Vec3(4.5, 1.0, 5.5))), "Unsupported work position must be rejected");
        helper.assertTrue(!NpcBlockInteraction.canWorkAt(npc, helper.absolutePos(elevatedLog),
                helper.absoluteVec(new Vec3(6.5, 1.0, 5.5))), "Cramped work position must be rejected");
        double floorY = npc.getY();
        helper.onEachTick(() -> helper.assertTrue(npc.getY() >= floorY - 0.05,
                "NPC must use a supported route and work position"));
        helper.succeedWhen(() -> {
            helper.assertBlockPresent(Blocks.AIR, elevatedLog);
            helper.assertItemEntityCountIs(Items.OAK_LOG, elevatedLog, 5.0, 1);
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
        NpcEntity npc = helper.spawn(ModEntities.NPC.get(), new BlockPos(2, 1, 5));

        helper.runAtTickTime(80, () -> {
            helper.assertBlockPresent(Blocks.OAK_LOG, enclosedLog);
            helper.assertItemEntityNotPresent(Items.OAK_LOG);
            helper.assertTrue(npc.isBlockTargetOnCooldown(helper.absolutePos(enclosedLog)),
                    "A resource with no reachable standing position must cool down");
            helper.assertTrue(!npc.hasBlockTarget() && !npc.hasMoveTarget(),
                    "Unreachable work positions must not leave stale movement targets");
            helper.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void removedElevatedTargetClearsItsWorkPosition(GameTestHelper helper) {
        prepareFloor(helper);
        BlockPos elevatedLog = new BlockPos(8, 3, 5);
        helper.setBlock(elevatedLog, Blocks.OAK_LOG);
        NpcEntity npc = helper.spawn(ModEntities.NPC.get(), new BlockPos(2, 1, 5));

        helper.runAtTickTime(10, () -> {
            helper.assertTrue(npc.hasMoveTarget() && npc.hasBlockTarget(), "NPC must still be approaching");
            helper.assertTrue(!npc.getMoveTarget().equals(npc.getBlockTarget()), "Work position must differ from resource");
            helper.setBlock(elevatedLog, Blocks.AIR);
        });
        helper.runAtTickTime(30, () -> {
            helper.assertTrue(!npc.hasBlockTarget() && !npc.hasMoveTarget(),
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
        helper.spawn(ModEntities.NPC.get(), new BlockPos(4, 1, 5));

        helper.runAtTickTime(80, () -> {
            rule.set(originalValue, helper.getLevel().getServer());
            helper.assertBlockPresent(Blocks.OAK_LOG, LOG);
            helper.assertItemEntityNotPresent(Items.OAK_LOG);
            helper.succeed();
        });
    }

    private static void prepareFloor(GameTestHelper helper) {
        for (int x = 0; x < 12; x++) {
            for (int z = 0; z < 12; z++) {
                helper.setBlock(x, 0, z, Blocks.STONE);
            }
        }
    }
}
