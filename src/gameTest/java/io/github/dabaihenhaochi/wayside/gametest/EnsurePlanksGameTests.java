package io.github.dabaihenhaochi.wayside.gametest;

import io.github.dabaihenhaochi.wayside.Wayside;
import io.github.dabaihenhaochi.wayside.entity.NpcEntity;
import io.github.dabaihenhaochi.wayside.entity.task.EnsurePlanksTask;
import io.github.dabaihenhaochi.wayside.entity.task.NpcTaskRunner;
import io.github.dabaihenhaochi.wayside.registry.ModEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Regression tests for inventory-driven plank production. */
@GameTestHolder(Wayside.MODID)
@PrefixGameTestTemplate(false)
public final class EnsurePlanksGameTests {

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void existingPlanksCompleteWithoutCrafting(GameTestHelper helper) {
        prepareFloor(helper);
        NpcEntity npc = helper.spawn(ModEntities.NPC.get(), new BlockPos(4, 1, 5));
        npc.getInventory().setItem(0, new ItemStack(Items.OAK_PLANKS, 20));
        npc.getTaskRunner().setTask(new EnsurePlanksTask(20));

        helper.runAtTickTime(30, () -> {
            helper.assertTrue(npc.getTaskRunner().state() == NpcTaskRunner.State.COMPLETED,
                    "Already-satisfied requirement should complete");
            helper.assertTrue(npc.getPlankCount() == 20, "Existing planks must be untouched");
            helper.assertTrue(npc.getLogCount() == 0, "No logs should be gathered");
            helper.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 220)
    public static void existingLogsCraftOnlyTheMissingPlanks(GameTestHelper helper) {
        prepareFloor(helper);
        NpcEntity npc = helper.spawn(ModEntities.NPC.get(), new BlockPos(4, 1, 5));
        npc.getInventory().setItem(0, new ItemStack(Items.OAK_PLANKS, 8));
        npc.getInventory().setItem(1, new ItemStack(Items.OAK_LOG, 3));
        npc.getTaskRunner().setTask(new EnsurePlanksTask(20));

        helper.succeedWhen(() -> {
            helper.assertTrue(npc.getTaskRunner().state() == NpcTaskRunner.State.COMPLETED,
                    "Task must complete using available logs");
            helper.assertTrue(npc.getPlankCount() == 20,
                    "Only 12 more planks must be produced");
            helper.assertTrue(npc.getLogCount() == 0, "Exactly three logs should be consumed");
        });
    }

    @GameTest(template = "empty", timeoutTicks = 600)
    public static void gathersOnlyTheMissingLogs(GameTestHelper helper) {
        prepareFloor(helper);
        BlockPos firstLog = new BlockPos(5, 1, 5);
        BlockPos secondLog = new BlockPos(7, 1, 5);
        helper.setBlock(firstLog, Blocks.OAK_LOG);
        helper.setBlock(secondLog, Blocks.OAK_LOG);

        NpcEntity npc = helper.spawn(ModEntities.NPC.get(), new BlockPos(4, 1, 5));
        npc.getInventory().setItem(0, new ItemStack(Items.OAK_PLANKS, 8));
        npc.getInventory().setItem(1, new ItemStack(Items.OAK_LOG, 1));
        npc.getTaskRunner().setTask(new EnsurePlanksTask(20));

        helper.succeedWhen(() -> {
            helper.assertTrue(npc.getTaskRunner().state() == NpcTaskRunner.State.COMPLETED,
                    "Two additional logs should satisfy a 12-plank deficit");
            helper.assertBlockPresent(Blocks.AIR, firstLog);
            helper.assertBlockPresent(Blocks.AIR, secondLog);
            helper.assertTrue(npc.getPlankCount() == 20,
                    "Task must stop once the requirement is met");
        });
    }

    @GameTest(template = "empty", timeoutTicks = 180)
    public static void savedRequirementIsRestored(GameTestHelper helper) {
        prepareFloor(helper);
        NpcEntity npc = helper.spawn(ModEntities.NPC.get(), new BlockPos(4, 1, 5));
        npc.getInventory().setItem(0, new ItemStack(Items.OAK_PLANKS, 4));
        npc.getInventory().setItem(1, new ItemStack(Items.OAK_LOG, 2));
        NpcTaskRunner runner = npc.getTaskRunner();
        runner.setTask(new EnsurePlanksTask(12));

        CompoundTag entityTag = new CompoundTag();
        entityTag.put("WaysideTask", runner.save());
        runner.load(entityTag);

        helper.succeedWhen(() -> {
            helper.assertTrue(runner.state() == NpcTaskRunner.State.COMPLETED,
                    "Restored task must complete");
            helper.assertTrue(npc.getPlankCount() == 12,
                    "Saved 12-plank target must not reset to the 20-plank default");
            helper.assertTrue(npc.getLogCount() == 0, "Existing logs should be sufficient");
        });
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void pauseRetainsUnreachableTargetCooldown(GameTestHelper helper) {
        prepareFloor(helper);
        NpcEntity npc = helper.spawn(ModEntities.NPC.get(), new BlockPos(4, 1, 5));
        EnsurePlanksTask task = new EnsurePlanksTask(20);
        NpcTaskRunner runner = npc.getTaskRunner();
        runner.setTask(task);
        runner.tick(); // Creates the gathering child even when no logs exist.

        BlockPos blocked = helper.absolutePos(new BlockPos(5, 1, 5));
        task.markBlockFailed(blocked, npc.level().getGameTime());
        helper.assertTrue(runner.isBlockOnCooldown(blocked),
                "Cooldown should exist before pause");
        runner.pause();
        helper.assertTrue(runner.isBlockOnCooldown(blocked),
                "Pause must not erase the gathering child's cooldown");
        runner.resume();
        helper.assertTrue(runner.isBlockOnCooldown(blocked),
                "Resuming the objective must not reset retry suppression");
        helper.succeed();
    }

    private static void prepareFloor(GameTestHelper helper) {
        for (int x = 0; x < 12; x++) {
            for (int z = 0; z < 12; z++) {
                helper.setBlock(x, 0, z, Blocks.STONE);
            }
        }
    }
}
