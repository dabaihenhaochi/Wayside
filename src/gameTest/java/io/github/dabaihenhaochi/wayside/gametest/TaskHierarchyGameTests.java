package io.github.dabaihenhaochi.wayside.gametest;

import io.github.dabaihenhaochi.wayside.Wayside;
import io.github.dabaihenhaochi.wayside.entity.NpcEntity;
import io.github.dabaihenhaochi.wayside.entity.action.ActionResult;
import io.github.dabaihenhaochi.wayside.entity.task.NpcTask;
import io.github.dabaihenhaochi.wayside.entity.task.NpcTaskRunner;
import io.github.dabaihenhaochi.wayside.entity.task.TaskSequence;
import io.github.dabaihenhaochi.wayside.registry.ModEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;

@GameTestHolder(Wayside.MODID)
@PrefixGameTestTemplate(false)
public final class TaskHierarchyGameTests {
    private static NpcEntity npc(GameTestHelper helper) {
        NpcEntity npc = helper.spawn(ModEntities.NPC.get(), new BlockPos(2, 1, 2));
        npc.getTaskRunner().cancel();
        return npc;
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void fourLevelsExecuteInOrderWithOneLeafPerTick(GameTestHelper helper) {
        NpcEntity npc = npc(helper);
        var trace = new ArrayList<String>();
        var root = new TaskSequence("root", List.of(
                new TaskSequence("house", List.of(
                        new TaskSequence("materials", List.of(
                                new TaskSequence("resources", List.of(new Probe("gather", trace, 2))),
                                new Probe("craft", trace, 1))),
                        new Probe("build", trace, 1))),
                new Probe("next_goal", trace, 1)));
        helper.assertTrue(root.tick(npc).isRunning() && trace.equals(List.of("gather")), "Only the deepest active leaf may tick");
        root.tick(npc);
        helper.assertTrue(trace.equals(List.of("gather", "gather")), "Completing a nested chain must not start a sibling in the same tick");
        root.tick(npc);
        root.tick(npc);
        helper.assertTrue(root.tick(npc).isSuccess() && trace.equals(List.of("gather", "gather", "craft", "build", "next_goal")),
                "Outer goals must wait for the complete nested chain");
        root.tick(npc);
        helper.assertTrue(trace.size() == 5, "A completed chain must not repeat its work");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void nestedFailureStopsSiblingsAndRetryKeepsEarlierWork(GameTestHelper helper) {
        NpcEntity npc = npc(helper);
        var trace = new ArrayList<String>();
        Probe failing = new Probe("fail_once", trace, 2);
        failing.failOnce = true;
        var root = new TaskSequence("root", List.of(new Probe("done", trace, 1),
                new TaskSequence("nested", List.of(failing, new Probe("later", trace, 1)))));
        var runner = new NpcTaskRunner(npc, () -> root);
        runner.tick();
        runner.tick();
        helper.assertTrue(runner.state() == NpcTaskRunner.State.FAILED
                        && runner.lastResult().failure() == ActionResult.Failure.UNREACHABLE,
                "The original leaf failure must propagate through every parent");
        runner.tick();
        helper.assertTrue(trace.equals(List.of("done", "fail_once")), "Failure must stop later steps until explicitly retried");
        runner.retry();
        runner.tick();
        runner.tick();
        helper.assertTrue(runner.state() == NpcTaskRunner.State.COMPLETED
                        && trace.equals(List.of("done", "fail_once", "fail_once", "later")),
                "Retry must continue the failed branch without repeating completed siblings");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void pauseAndTargetsReachOnlyTheActiveLeaf(GameTestHelper helper) {
        NpcEntity npc = npc(helper);
        Probe active = new Probe("active", new ArrayList<>(), 3);
        Probe future = new Probe("future", new ArrayList<>(), 1);
        var root = new TaskSequence("root", List.of(new TaskSequence("nested", List.of(active, future))));
        var runner = new NpcTaskRunner(npc, () -> root);
        runner.tick();
        helper.assertTrue(runner.activeMoveTarget().equals(active.target) && runner.activeBlockTarget().equals(active.target),
                "Deep targets must be visible through all parent tasks");
        runner.pause();
        runner.tick();
        helper.assertTrue(active.ticks == 1 && active.pauses == 1 && future.ticks == 0 && future.pauses == 0,
                "Pausing must stop exactly the active branch and leave future tasks untouched");
        runner.resume();
        runner.tick();
        helper.assertTrue(active.ticks == 2, "Resume must keep the same active child");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void lazyFactoriesReadPreviousResultsAndStayIdleDuringQueries(GameTestHelper helper) {
        NpcEntity npc = npc(helper);
        int[] value = {0};
        int[] created = {0};
        var root = new TaskSequence("root",
                TaskSequence.step("set", () -> { created[0]++; return new Probe("set", new ArrayList<>(), 1) {
                    @Override public ActionResult tick(NpcEntity actor) { value[0] = 7; return ActionResult.success(); }
                }; }),
                TaskSequence.step("use", () -> {
                    created[0]++;
                    helper.assertTrue(value[0] == 7, "Later factories must observe the previous task's output");
                    return new Probe("use", new ArrayList<>(), 1);
                }));
        root.save(); root.stageName(); root.activeMoveTarget(); root.activeBlockTarget(); root.pause(npc);
        helper.assertTrue(created[0] == 0, "Inspection and pause must not create unstarted tasks");
        root.tick(npc);
        root.save(); root.stageName(); root.pause(npc);
        helper.assertTrue(created[0] == 1, "The next task must wait for its execution tick");
        helper.assertTrue(root.tick(npc).isSuccess() && created[0] == 2, "Each lazy child must be created once");
        helper.succeed();
    }

    private static TaskSequence resumable(List<String> trace) {
        return new TaskSequence("root",
                TaskSequence.step("first", () -> new Probe("first", trace, 1)),
                TaskSequence.step("nested", () -> new TaskSequence("nested",
                        TaskSequence.step("work", () -> new Probe("work", trace, 3)),
                        TaskSequence.step("last", () -> new Probe("last", trace, 1)))));
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void nestedProgressSurvivesTwoReloadsBeforeTicking(GameTestHelper helper) {
        NpcEntity npc = npc(helper);
        var trace = new ArrayList<String>();
        var original = resumable(trace);
        original.tick(npc);
        original.tick(npc);
        original.tick(npc);
        CompoundTag saved = original.save();
        var restored = resumable(trace);
        restored.load(saved);
        var restoredAgain = resumable(trace);
        restoredAgain.load(restored.save());
        helper.assertTrue(restoredAgain.tick(npc).isRunning(), "The partially completed nested task must resume");
        helper.assertTrue(restoredAgain.tick(npc).isSuccess()
                        && trace.equals(List.of("first", "work", "work", "work", "last")),
                "Reloading before the first tick must preserve pending child data without repeating completed work");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void completedSequenceReloadDoesNotCreateChildren(GameTestHelper helper) {
        NpcEntity npc = npc(helper);
        var trace = new ArrayList<String>();
        var original = resumable(trace);
        for (int i = 0; i < 8; i++) original.tick(npc);
        var restored = resumable(trace);
        restored.load(original.save());
        restored.pause(npc);
        helper.assertTrue(restored.tick(npc).isSuccess() && trace.size() == 5
                        && restored.activeMoveTarget() == null,
                "A completed parent must remain completed after reload");
        helper.succeed();
    }

    private static class Probe implements NpcTask {
        private final String name;
        private final List<String> trace;
        private final int finishAt;
        private final BlockPos target = new BlockPos(1, 2, 3);
        private int ticks;
        private int pauses;
        private boolean failOnce;
        private Probe(String name, List<String> trace, int finishAt) { this.name = name; this.trace = trace; this.finishAt = finishAt; }
        @Override public String typeId() { return name; }
        @Override public String stageName() { return name; }
        @Override public ActionResult tick(NpcEntity npc) {
            ticks++; trace.add(name);
            if (failOnce && ticks == 1) return ActionResult.failed(ActionResult.Failure.UNREACHABLE);
            return ticks >= finishAt ? ActionResult.success() : ActionResult.running();
        }
        @Override public void pause(NpcEntity npc) { pauses++; }
        @Override public BlockPos activeBlockTarget() { return target; }
        @Override public BlockPos activeMoveTarget() { return target; }
        @Override public CompoundTag save() { CompoundTag tag = new CompoundTag(); tag.putInt("Ticks", ticks); return tag; }
        @Override public void load(CompoundTag tag) { ticks = tag.getInt("Ticks"); }
    }
}
