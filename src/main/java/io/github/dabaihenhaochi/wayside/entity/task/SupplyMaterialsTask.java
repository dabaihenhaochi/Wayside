package io.github.dabaihenhaochi.wayside.entity.task;

import io.github.dabaihenhaochi.wayside.entity.NpcEntity;
import io.github.dabaihenhaochi.wayside.entity.action.ActionResult;
import io.github.dabaihenhaochi.wayside.entity.action.CraftRecipeAction;
import io.github.dabaihenhaochi.wayside.material.MaterialPlan;
import io.github.dabaihenhaochi.wayside.material.MaterialPlanner;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

// 根据背包规划采集和合成，目前仅能采集橡木；中断后按实际库存重新规划。
public final class SupplyMaterialsTask implements NpcTask {
    public static final String ID = "wayside:supply_materials";
    private final Map<Item, Integer> requested = new LinkedHashMap<>();

    private MaterialPlan plan;
    private int craftStepIndex;
    private CraftRecipeAction craftAction;
    private GatherResourceTask gathering;
    private int gatherTarget;

    // 供存档工厂创建，随后加载保存的需求。
    public SupplyMaterialsTask() {
        this(Map.of(Items.OAK_STAIRS, 8));
    }

    public SupplyMaterialsTask(Map<Item, Integer> requested) {
        setRequested(requested);
    }

    // 先校验全部需求，再替换为独立副本。
    private void setRequested(Map<Item, Integer> items) {
        Objects.requireNonNull(items);
        if (items.isEmpty()) throw new IllegalArgumentException("Empty material request");
        Map<Item, Integer> copy = new LinkedHashMap<>();
        for (var entry : items.entrySet()) {
            Item item = Objects.requireNonNull(entry.getKey());
            Integer amount = Objects.requireNonNull(entry.getValue());
            if (item == Items.AIR || amount <= 0) {
                throw new IllegalArgumentException("Invalid material count or item");
            }
            copy.put(item, amount);
        }
        requested.clear();
        requested.putAll(copy);
    }

    @Override public String typeId() { return ID; }

    @Override public String stageName() {
        if (gathering != null) return "GATHERING";
        if (craftAction != null) return "CRAFTING";
        return "PLANNING";
    }

    // 依次完成规划、补齐原木、执行配方，最后核验实际库存达标。
    @Override
    public ActionResult tick(NpcEntity npc) {
        if (!(npc.level() instanceof ServerLevel level)) {
            return ActionResult.failed(ActionResult.Failure.UNKNOWN);
        }

        if (plan == null) {
            plan = MaterialPlanner.plan(
                    level, requested, npc.getInventory(), Set.of(Items.OAK_LOG));
            craftStepIndex = 0;
            craftAction = null;
            // 在规划时固定原木总量目标，避免每次拾取后目标随之增加。
            int newTarget = count(npc.getInventory(), Items.OAK_LOG)
                    + plan.requiredSupplies().getOrDefault(Items.OAK_LOG, 0);
            if (gathering != null && gatherTarget != newTarget) {
                gathering.pause(npc);
                gathering = null;
            }
            gatherTarget = newTarget;
            if (!plan.isFeasible()) {
                return ActionResult.failed(ActionResult.Failure.MISSING_INGREDIENT);
            }
        }

        int additionalLogs = plan.requiredSupplies().getOrDefault(Items.OAK_LOG, 0);
        // 当前只提供橡木采集，其他外部材料需求直接报告失败。
        if (plan.requiredSupplies().size() > (additionalLogs > 0 ? 1 : 0)) {
            return ActionResult.failed(ActionResult.Failure.MISSING_INGREDIENT);
        }

        if (additionalLogs > 0) {
            if (gathering == null) {
                gathering = new GatherResourceTask(
                        state -> state.is(Blocks.OAK_LOG),
                        stack -> stack.is(Items.OAK_LOG),
                        actor -> count(actor.getInventory(), Items.OAK_LOG),
                        gatherTarget);
            }
            // 本次计划沿用固定采集目标，直到重新规划。
            ActionResult gathered = gathering.tick(npc);
            if (gathered.isFailed()) return gathered;
            if (!gathered.isSuccess()) return ActionResult.running();

            gathering.pause(npc);
            gathering = null;
            gatherTarget = 0;
            // 补给到位后清空待补项，避免下一 tick 再次创建采集任务。
            plan = new MaterialPlan(plan.craftSteps(), Map.of(), plan.unavailable());
        } else if (gathering != null) {
            gathering.pause(npc);
            gathering = null;
            gatherTarget = 0;
        }

        if (craftStepIndex < plan.craftSteps().size()) {
            if (craftAction == null) {
                craftAction = new CraftRecipeAction(plan.craftSteps().get(craftStepIndex));
            }
            ActionResult result = craftAction.tick(npc);
            if (result.isFailed()) return result;
            if (result.isSuccess()) {
                craftAction.stop(npc);
                craftAction = null;
                craftStepIndex++;
            }
            return ActionResult.running();
        }

        // 计划基于旧库存快照，结束前再次核验真实数量。
        for (var entry : requested.entrySet()) {
            if (count(npc.getInventory(), entry.getKey()) < entry.getValue()) {
                return ActionResult.failed(ActionResult.Failure.WORLD_CHANGED);
            }
        }
        return ActionResult.success();
    }

    private static int count(SimpleContainer inventory, Item item) {
        int total = 0;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty() && stack.is(item)) total += stack.getCount();
        }
        return total;
    }

    @Override
    public void pause(NpcEntity npc) {
        if (gathering != null) {
            gathering.pause(npc);
        }
        if (craftAction != null) {
            craftAction.stop(npc);
            craftAction = null;
        }
        // 已完成合成已写入背包，继续时重新规划，避免重复执行。
        plan = null;
        craftStepIndex = 0;
    }

    @Override public BlockPos activeBlockTarget() {
        return gathering == null ? null : gathering.activeBlockTarget();
    }

    @Override public BlockPos activeMoveTarget() {
        return gathering == null ? null : gathering.activeMoveTarget();
    }

    @Override public void abandonWorkTarget(NpcEntity npc) {
        if (gathering != null) gathering.abandonWorkTarget(npc);
    }

    @Override public boolean isBlockOnCooldown(BlockPos pos, long now) {
        return gathering != null && gathering.isBlockOnCooldown(pos, now);
    }

    @Override public void markBlockFailed(BlockPos pos, long now) {
        if (gathering != null) gathering.markBlockFailed(pos, now);
    }

    // 只保存最终物品需求，临时采集和合成计划不写入存档。
    @Override
    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        ListTag list = new ListTag();
        for (var entry : requested.entrySet()) {
            CompoundTag item = new CompoundTag();
            item.putString("Item", BuiltInRegistries.ITEM.getKey(entry.getKey()).toString());
            item.putInt("Count", entry.getValue());
            list.add(item);
        }
        tag.put("Requested", list);
        return tag;
    }

    // 过滤无效需求并合并重复物品，清空临时状态以便重新规划。
    @Override
    public void load(CompoundTag tag) {
        if (tag.contains("Requested", Tag.TAG_LIST)) {
            Map<Item, Integer> loaded = new LinkedHashMap<>();
            ListTag list = tag.getList("Requested", Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++) {
                CompoundTag item = list.getCompound(i);
                ResourceLocation id = ResourceLocation.tryParse(item.getString("Item"));
                int amount = item.getInt("Count");
                if (id == null || amount <= 0) continue;
                BuiltInRegistries.ITEM.getOptional(id).ifPresent(value -> {
                    if (value != Items.AIR) loaded.merge(value, amount, Integer::sum);
                });
            }
            if (!loaded.isEmpty()) setRequested(loaded);
        }
        plan = null;
        gathering = null;
        gatherTarget = 0;
        craftAction = null;
        craftStepIndex = 0;
    }
}
