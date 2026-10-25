package io.github.dabaihenhaochi.wayside.entity.task;

import io.github.dabaihenhaochi.wayside.entity.NpcEntity;
import io.github.dabaihenhaochi.wayside.entity.action.ActionResult;
import io.github.dabaihenhaochi.wayside.entity.action.CraftSingleIngredientAction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.RecipeType;

import java.util.List;

// 按木板总量补齐缺口，缺原木时先采集，木板达标即停止。
public final class EnsurePlanksTask implements NpcTask {
    public static final String ID = "wayside:ensure_planks";
    private ItemRequirement requirement;
    private GatherResourceTask gathering;
    private int gatheringTarget;
    private CraftSingleIngredientAction crafting;

    public EnsurePlanksTask() { this(20); }
    public EnsurePlanksTask(int count) {
        requirement = new ItemRequirement(ItemTags.PLANKS, count);
    }

    @Override public String typeId() { return ID; }
    @Override public String stageName() {
        return gathering != null ? "GATHERING" : "CRAFTING_OR_CHECKING";
    }

    // 查询一根原木的木板产量，用于估算缺口。
    private int plankYield(NpcEntity npc) {
        ItemStack sample = new ItemStack(Items.OAK_LOG);
        // 优先使用背包中的原木；尚无原木时以橡木估算。
        for (int i = 0; i < npc.getInventory().getContainerSize(); i++) {
            ItemStack available = npc.getInventory().getItem(i);
            if (available.is(ItemTags.LOGS)) {
                sample = available.copyWithCount(1);
                break;
            }
        }
        CraftingInput input = CraftingInput.of(1, 1, List.of(sample));
        var recipe = npc.level().getRecipeManager().getRecipeFor(
                RecipeType.CRAFTING, input, npc.level());
        if (recipe.isEmpty()) return 0;
        ItemStack result = recipe.get().value().assemble(input, npc.level().registryAccess());
        return result.is(ItemTags.PLANKS) ? result.getCount() : 0;
    }

    // 按木板缺口向上取整所需原木数，再切换采集或合成。
    @Override public ActionResult tick(NpcEntity npc) {
        int missing = requirement.missing(npc.getInventory());
        if (missing == 0) {
            pause(npc);
            return ActionResult.success();
        }

        int yield = plankYield(npc);
        if (yield <= 0) return ActionResult.failed(ActionResult.Failure.NO_RECIPE);
        int logsNeeded = (missing + yield - 1) / yield;
        if (npc.getLogCount() < logsNeeded) {
            if (crafting != null) {
                crafting.stop(npc);
                crafting = null;
            }
            if (gathering == null || gatheringTarget != logsNeeded) {
                if (gathering != null) gathering.pause(npc);
                gatheringTarget = logsNeeded;
                gathering = new GatherResourceTask(
                        state -> state.is(BlockTags.LOGS),
                        stack -> stack.is(ItemTags.LOGS),
                        NpcEntity::getLogCount,
                        logsNeeded);
            }
            ActionResult result = gathering.tick(npc);
            if (result.isFailed()) return result;
            if (result.isSuccess()) {
                gathering.pause(npc);
                gathering = null;
                gatheringTarget = 0;
            }
            return ActionResult.running();
        }

        if (gathering != null) {
            gathering.pause(npc);
            gathering = null;
            gatheringTarget = 0;
        }
        if (crafting == null) {
            crafting = new CraftSingleIngredientAction(
                    stack -> stack.is(ItemTags.LOGS),
                    stack -> stack.is(ItemTags.PLANKS));
        }
        ActionResult result = crafting.tick(npc);
        return result.isSuccess() && requirement.missing(npc.getInventory()) > 0
                ? ActionResult.failed(ActionResult.Failure.NO_RECIPE) : result;
    }

    @Override public void pause(NpcEntity npc) {
        // 暂停时清理临时动作，保留采集子任务的失败冷却。
        if (gathering != null) {
            gathering.pause(npc);
        }
        if (crafting != null) {
            crafting.stop(npc);
            crafting = null;
        }
    }

    @Override public net.minecraft.core.BlockPos activeBlockTarget() {
        return gathering == null ? null : gathering.activeBlockTarget();
    }
    @Override public net.minecraft.core.BlockPos activeMoveTarget() {
        return gathering == null ? null : gathering.activeMoveTarget();
    }
    @Override public boolean isBlockOnCooldown(net.minecraft.core.BlockPos pos, long now) {
        return gathering != null && gathering.isBlockOnCooldown(pos, now);
    }
    @Override public void markBlockFailed(net.minecraft.core.BlockPos pos, long now) {
        if (gathering != null) gathering.markBlockFailed(pos, now);
    }
    @Override public void abandonWorkTarget(NpcEntity npc) {
        if (gathering != null) gathering.abandonWorkTarget(npc);
    }

    @Override public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("RequiredPlanks", requirement.totalCount());
        return tag;
    }
    @Override public void load(CompoundTag tag) {
        int count = tag.getInt("RequiredPlanks");
        if (count > 0) requirement = new ItemRequirement(ItemTags.PLANKS, count);
    }
}
