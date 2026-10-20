package io.github.dabaihenhaochi.wayside.entity.task;

import io.github.dabaihenhaochi.wayside.entity.NpcEntity;
import io.github.dabaihenhaochi.wayside.entity.action.ActionResult;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;

/** One long-running objective. Owns its sequence of reusable actions. */
public interface NpcTask {
    String typeId();
    ActionResult tick(NpcEntity npc);
    void pause(NpcEntity npc);
    String stageName();

    default boolean wantsItem(ItemStack item) { return false; }
    default BlockPos activeBlockTarget() { return null; }
    default BlockPos activeMoveTarget() { return null; }
    default void abandonWorkTarget(NpcEntity npc) {}
    default boolean isBlockOnCooldown(BlockPos pos, long now) { return false; }
    default void markBlockFailed(BlockPos pos, long now) {}
    default CompoundTag save() { return new CompoundTag(); }
    default void load(CompoundTag tag) {}
}
