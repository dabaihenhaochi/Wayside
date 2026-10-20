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
