package io.github.dabaihenhaochi.wayside.entity.task.resource;

import io.github.dabaihenhaochi.wayside.entity.NpcEntity;
import io.github.dabaihenhaochi.wayside.entity.action.ActionResult;
import io.github.dabaihenhaochi.wayside.entity.task.CompositeTask;
import io.github.dabaihenhaochi.wayside.entity.task.NpcTask;
import io.github.dabaihenhaochi.wayside.entity.task.TaskSequence;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.RecipeType;

import java.util.List;

/** 判断木板总量，不足时执行内部的“补原木 → 合成到目标”任务链。 */
public final class EnsurePlanksTask extends CompositeTask {
    public static final String ID = "wayside:ensure_planks";
    private ItemRequirement requirement;
    private TaskSequence production;

    public EnsurePlanksTask() { this(20); }
    public EnsurePlanksTask(int count) { requirement = new ItemRequirement(ItemTags.PLANKS, count); }
    @Override public String typeId() { return ID; }
    @Override protected NpcTask activeTask() { return production; }
    @Override public String stageName() { return production == null ? "CHECKING_PLANKS" : production.stageName(); }

    @Override public ActionResult tick(NpcEntity npc) {
        int missing = requirement.missing(npc.getInventory());
        if (missing == 0) return ActionResult.success(); // 每 tick 先检查目标，达标就停止内部合成。
        if (production == null) {
            int yield = plankYield(npc);
            if (yield <= 0) return ActionResult.failed(ActionResult.Failure.NO_RECIPE);
            int logsNeeded = (missing + yield - 1) / yield;
            production = new TaskSequence("wayside:plank_production", List.of(
                    new GatherResourceTask(state -> state.is(BlockTags.LOGS), stack -> stack.is(ItemTags.LOGS), logsNeeded),
                    new CraftUntilExhaustedTask(stack -> stack.is(ItemTags.LOGS), stack -> stack.is(ItemTags.PLANKS))));
        }
        ActionResult result = production.tick(npc);
        return result.isSuccess() && requirement.missing(npc.getInventory()) > 0
                ? ActionResult.failed(ActionResult.Failure.NO_RECIPE) : result;
    }

    private int plankYield(NpcEntity npc) {
        ItemStack sample = new ItemStack(Items.OAK_LOG);
        for (int i = 0; i < npc.getInventory().getContainerSize(); i++) {
            ItemStack available = npc.getInventory().getItem(i);
            if (available.is(ItemTags.LOGS)) { sample = available.copyWithCount(1); break; }
        }
        CraftingInput input = CraftingInput.of(1, 1, List.of(sample));
        var recipe = npc.level().getRecipeManager().getRecipeFor(RecipeType.CRAFTING, input, npc.level());
        if (recipe.isEmpty()) return 0;
        ItemStack result = recipe.get().value().assemble(input, npc.level().registryAccess());
        return result.is(ItemTags.PLANKS) ? result.getCount() : 0;
    }

    @Override public void pause(NpcEntity npc) {
        super.pause(npc);
        production = null;
    }
    @Override public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("RequiredPlanks", requirement.totalCount());
        return tag;
    }
    @Override public void load(CompoundTag tag) {
        int count = tag.getInt("RequiredPlanks");
        if (count > 0) requirement = new ItemRequirement(ItemTags.PLANKS, count);
        production = null;
    }

}
