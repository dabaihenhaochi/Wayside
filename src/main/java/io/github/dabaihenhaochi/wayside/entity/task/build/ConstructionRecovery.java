package io.github.dabaihenhaochi.wayside.entity.task.build;

import io.github.dabaihenhaochi.wayside.building.BuildingMaterialCalculator;
import io.github.dabaihenhaochi.wayside.building.BuildingManager;
import io.github.dabaihenhaochi.wayside.entity.NpcEntity;
import io.github.dabaihenhaochi.wayside.entity.action.ActionResult;
import io.github.dabaihenhaochi.wayside.entity.action.PillarUpAction;
import io.github.dabaihenhaochi.wayside.entity.inventory.InventoryOps;
import io.github.dabaihenhaochi.wayside.entity.task.FailureMemory;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Items;
import java.util.function.Predicate;

/** 施工辅助策略及进度：预留泥土、限制连续垫高、记录失败位置。 */
final class ConstructionRecovery {
    private static final int MAX_RISE = 8;
    private final FailureMemory<BlockPos> failures = new FailureMemory<>();
    private int reservedDirt;
    private int consecutiveRise;
    private BlockPos base;
    private PillarUpAction active;

    void reserve(BuildingMaterialCalculator.Result materials) {
        reservedDirt = materials.requiredItems().getOrDefault(Items.DIRT, 0);
    }

    PillarUpAction tryStart(NpcEntity npc, ConstructionWorkSelector selector, Predicate<BlockPos> allowed, long now) {
        int spare = InventoryOps.count(npc.getInventory(), Items.DIRT) - reservedDirt;
        int maxRise = Math.min(spare, MAX_RISE - consecutiveRise);
        if (maxRise < 1) return null;
        var buildings = new BuildingManager((ServerLevel) npc.level());
        Predicate<BlockPos> outsideBuildings = pos -> allowed.test(pos)
                && buildings.isPositionAvailable(pos);
        base = selector.findPillarBase(npc, maxRise,
                pos -> outsideBuildings.test(pos) && !failures.isCoolingDown(pos, now));
        active = base == null ? null : new PillarUpAction(base, outsideBuildings, reservedDirt);
        return active;
    }

    void finished(ActionResult result, long now) {
        if (result.isSuccess() || hasPlacedBlock()) consecutiveRise++;
        else if (base != null) failures.mark(base, now, 80);
        base = null;
        active = null;
    }
    void workCompleted() { consecutiveRise = 0; }
    BlockPos activeTarget() { return base; }
    private boolean hasPlacedBlock() { return active != null && active.hasPlacedBlock(); }
    void clearActive() {
        if (hasPlacedBlock()) consecutiveRise++;
        base = null;
        active = null;
    }
    CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("ConsecutiveRise", consecutiveRise + (hasPlacedBlock() ? 1 : 0));
        return tag;
    }
    void load(CompoundTag tag) {
        consecutiveRise = Math.max(0, Math.min(MAX_RISE, tag.getInt("ConsecutiveRise")));
        base = null;
        active = null;
    }
}
