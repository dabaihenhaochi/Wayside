package io.github.dabaihenhaochi.wayside.gametest;

import io.github.dabaihenhaochi.wayside.Wayside;
import io.github.dabaihenhaochi.wayside.entity.NpcEntity;
import io.github.dabaihenhaochi.wayside.entity.action.ActionResult;
import io.github.dabaihenhaochi.wayside.entity.task.NpcTask;
import io.github.dabaihenhaochi.wayside.entity.task.NpcTaskRunner;
import io.github.dabaihenhaochi.wayside.registry.ModEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Task-runner lifecycle tests, independent of wood gathering or building. */
@GameTestHolder(Wayside.MODID)
@PrefixGameTestTemplate(false)
public class NpcTaskLifecycleGameTests {
    @GameTest(template = "empty", timeoutTicks = 100)
    public static void configuredDefaultIsUsedForNewAndUnknownTasks(GameTestHelper helper) {
        NpcEntity npc = helper.spawn(ModEntities.NPC.get(), new BlockPos(2, 1, 2));
        npc.getTaskRunner().cancel();
        CountingTask[] created = {null};
        NpcTaskRunner runner = new NpcTaskRunner(npc, () -> {
            created[0] = new CountingTask(false);
            return created[0];
        });
        CountingTask first = created[0];
        runner.tick();
        helper.assertTrue(first.ticks == 1, "Runner must execute the caller's default task");
        CompoundTag unknown = new CompoundTag();
        unknown.putString("Type", "wayside:unknown_test_task");
        CompoundTag tag = new CompoundTag();
        tag.put("WaysideTask", unknown);
        runner.load(tag);
        helper.assertTrue(created[0] != first, "Unknown task must obtain a fresh configured default");
        runner.tick();
        helper.assertTrue(created[0].ticks == 1 && first.ticks == 1,
                "Restoration must execute the new default rather than resume the old task");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void legacyTaskStageStillRestoresThroughRegistry(GameTestHelper helper) {
        NpcEntity npc = helper.spawn(ModEntities.NPC.get(), new BlockPos(2, 1, 2));
        npc.getTaskRunner().cancel();
        NpcTaskRunner runner = new NpcTaskRunner(npc,
                io.github.dabaihenhaochi.wayside.entity.task.VisitPositionTask::new);
        CompoundTag tag = new CompoundTag();
        tag.putString("WaysideTaskStage", "COMPLETED");
        runner.load(tag);
        helper.assertTrue(runner.state() == NpcTaskRunner.State.COMPLETED
                        && runner.description().startsWith("wayside:gather_and_craft_planks") && !runner.hasWork(),
                "Legacy stage must restore its original task and completed lifecycle");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void legacyCraftingStageResumesWithoutGatheringAgain(GameTestHelper helper) {
        for (int x = 0; x < 5; x++) for (int z = 0; z < 5; z++) helper.setBlock(x, 0, z, net.minecraft.world.level.block.Blocks.STONE);
        NpcEntity npc = helper.spawn(ModEntities.NPC.get(), new BlockPos(2, 1, 2));
        npc.getInventory().clearContent();
        npc.getInventory().setItem(0, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.OAK_LOG, 1));
        CompoundTag legacy = new CompoundTag();
        legacy.putString("WaysideTaskStage", "CRAFTING");
        npc.getTaskRunner().load(legacy);
        helper.succeedWhen(() -> {
            helper.assertTrue(npc.getTaskRunner().state() == NpcTaskRunner.State.COMPLETED,
                    "The old crafting stage must finish without trying to gather sixteen logs again");
            helper.assertTrue(npc.getLogCount() == 0 && npc.getPlankCount() == 4,
                    "The restored crafting stage must consume the existing log exactly once");
        });
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void genericSequenceDoesNotInterpretResourceStageNames(GameTestHelper helper) {
        NpcEntity npc = helper.spawn(ModEntities.NPC.get(), new BlockPos(2, 1, 2));
        npc.getTaskRunner().cancel();
        CountingTask first = new CountingTask(false);
        var sequence = new io.github.dabaihenhaochi.wayside.entity.task.TaskSequence("wayside:test_sequence",
                java.util.List.of(first, new CountingTask(false)));
        CompoundTag unrelatedStage = new CompoundTag();
        unrelatedStage.putString("Stage", "COMPLETED");
        sequence.load(unrelatedStage);
        sequence.tick(npc);
        helper.assertTrue(first.ticks == 1, "A generic sequence must not mistake a resource-specific legacy field for its own progress");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void pauseResumeAndCancel(GameTestHelper helper) {
        NpcEntity npc = helper.spawn(ModEntities.NPC.get(), new BlockPos(2, 1, 2));
        CountingTask work = new CountingTask(false);
        NpcTaskRunner runner = npc.getTaskRunner();
        runner.setTask(work);
        runner.pause();

        helper.runAtTickTime(20, () -> {
            helper.assertTrue(work.ticks == 0, "Paused task must not tick");
            helper.assertTrue(runner.state() == NpcTaskRunner.State.PAUSED, "Task must be paused");
            runner.resume();
        });
        helper.runAtTickTime(40, () -> {
            helper.assertTrue(work.ticks > 0, "Resumed task must tick");
            runner.cancel();
            helper.assertTrue(runner.state() == NpcTaskRunner.State.CANCELED, "Task must be canceled");
        });
        helper.runAtTickTime(60, () -> {
            int before = work.ticks;
            runner.resume(); // Canceled tasks cannot be resumed.
            runner.tick();
            helper.assertTrue(work.ticks == before, "Canceled task must not resume");
            helper.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void failureRequiresExplicitRetry(GameTestHelper helper) {
        NpcEntity npc = helper.spawn(ModEntities.NPC.get(), new BlockPos(2, 1, 2));
        CountingTask work = new CountingTask(true);
        NpcTaskRunner runner = npc.getTaskRunner();
        runner.setTask(work);
        runner.tick();
        helper.assertTrue(runner.state() == NpcTaskRunner.State.FAILED, "Task must fail");
        helper.assertTrue(runner.lastResult().failure() == ActionResult.Failure.UNREACHABLE,
                "Failure reason must be available");
        int before = work.ticks;
        runner.tick();
        helper.assertTrue(work.ticks == before, "Failed task must not be rerun automatically");
        runner.retry();
        runner.tick();
        helper.assertTrue(work.ticks > before, "Explicit retry must restart task execution");
        helper.assertTrue(runner.state() == NpcTaskRunner.State.RUNNING, "Retry must resume task");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void canceledStateSurvivesSaveLoad(GameTestHelper helper) {
        NpcEntity npc = helper.spawn(ModEntities.NPC.get(), new BlockPos(2, 1, 2));
        NpcTaskRunner runner = npc.getTaskRunner();
        runner.cancel();
        CompoundTag tag = new CompoundTag();
        tag.put("WaysideTask", runner.save());
        runner.load(tag);
        helper.assertTrue(runner.state() == NpcTaskRunner.State.CANCELED,
                "Canceled state must survive reload");
        helper.assertTrue(!runner.hasWork(), "Canceled task must not execute");
        helper.succeed();
    }

    private static final class CountingTask implements NpcTask {
        private final boolean failOnce;
        private int ticks;

        private CountingTask(boolean failOnce) { this.failOnce = failOnce; }
        @Override public String typeId() { return "wayside:test_task"; }
        @Override public String stageName() { return "TEST"; }
        @Override public ActionResult tick(NpcEntity npc) {
            ticks++;
            return failOnce && ticks == 1
                    ? ActionResult.failed(ActionResult.Failure.UNREACHABLE)
                    : ActionResult.running();
        }
        @Override public void pause(NpcEntity npc) {}
    }
}
