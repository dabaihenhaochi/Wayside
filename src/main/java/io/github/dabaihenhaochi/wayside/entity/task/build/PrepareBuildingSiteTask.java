package io.github.dabaihenhaochi.wayside.entity.task.build;

import io.github.dabaihenhaochi.wayside.entity.task.NpcTask;

import io.github.dabaihenhaochi.wayside.building.BuildingSitePlan;
import io.github.dabaihenhaochi.wayside.entity.NpcEntity;
import io.github.dabaihenhaochi.wayside.entity.action.ActionResult;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;

import java.util.Objects;

/** 独立的整地任务。清障、填土沿用施工动作，填土必须从有支撑的底层开始。 */
public final class PrepareBuildingSiteTask implements NpcTask {
    public static final String ID = "wayside:prepare_building_site";
    private BuildingSitePlan site;
    private ConstructionExecutor executor = new ConstructionExecutor(true);

    public PrepareBuildingSiteTask() {}
    public PrepareBuildingSiteTask(BuildingSitePlan site) { this.site = Objects.requireNonNull(site); }
    @Override public String typeId() { return ID; }
    @Override public String stageName() { return "PREPARING_SITE_" + executor.stageName(); }
    @Override public BlockPos activeBlockTarget() { return executor.activeBlockTarget(); }
    @Override public BlockPos activeMoveTarget() { return executor.activeMoveTarget(); }
    @Override public void pause(NpcEntity npc) { executor.pause(npc); }
    @Override public ActionResult tick(NpcEntity npc) {
        if (!(npc.level() instanceof ServerLevel level) || site == null) return ActionResult.failed(ActionResult.Failure.INVALID_TARGET);
        return executor.tick(npc, () -> site.remaining(level));
    }
    @Override public CompoundTag save() { return site == null ? new CompoundTag() : site.save(); }
    @Override public void load(CompoundTag tag) {
        site = null;
        try { site = BuildingSitePlan.load(tag); }
        catch (IllegalArgumentException ignored) { /* tick 明确报告无效存档。 */ }
        executor = new ConstructionExecutor(true);
    }
}
