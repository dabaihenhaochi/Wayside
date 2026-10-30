package io.github.dabaihenhaochi.wayside.entity.task.resource;

import io.github.dabaihenhaochi.wayside.entity.NpcEntity;
import io.github.dabaihenhaochi.wayside.entity.action.ActionResult;
import io.github.dabaihenhaochi.wayside.entity.inventory.InventoryOps;
import io.github.dabaihenhaochi.wayside.entity.task.CompositeTask;
import io.github.dabaihenhaochi.wayside.entity.task.NpcTask;
import io.github.dabaihenhaochi.wayside.entity.task.TaskSequence;
import io.github.dabaihenhaochi.wayside.material.MaterialPlanner;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 判断最终材料缺口，再组合采集和配方任务。计划重建时始终以真实库存为准。 */
public final class SupplyMaterialsTask extends CompositeTask {
    public static final String ID = "wayside:supply_materials";
    private final Map<Item, Integer> requested = new LinkedHashMap<>();
    private TaskSequence production;

    public SupplyMaterialsTask() { this(Map.of(Items.OAK_STAIRS, 8)); }
    public SupplyMaterialsTask(Map<Item, Integer> requested) { setRequested(requested); }

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
    @Override protected NpcTask activeTask() { return production; }
    @Override public String stageName() { return production == null ? "PLANNING_MATERIALS" : production.stageName(); }

    @Override public ActionResult tick(NpcEntity npc) {
        if (!(npc.level() instanceof ServerLevel level)) return ActionResult.failed(ActionResult.Failure.INVALID_TARGET);
        if (isSatisfied(npc)) return ActionResult.success();
        if (production == null) {
            var plan = MaterialPlanner.plan(level, requested, npc.getInventory(), Set.of(Items.OAK_LOG));
            if (!plan.isFeasible() || plan.requiredSupplies().keySet().stream().anyMatch(item -> item != Items.OAK_LOG)) {
                return ActionResult.failed(ActionResult.Failure.MISSING_INGREDIENT);
            }
            var tasks = new ArrayList<NpcTask>();
            int logs = plan.requiredSupplies().getOrDefault(Items.OAK_LOG, 0);
            if (logs > 0) {
                if (!npc.getInventory().canAddItem(new ItemStack(Items.OAK_LOG))) return ActionResult.failed(ActionResult.Failure.NO_SPACE);
                int target = InventoryOps.count(npc.getInventory(), Items.OAK_LOG) + logs;
                tasks.add(new GatherResourceTask(state -> state.is(Blocks.OAK_LOG), stack -> stack.is(Items.OAK_LOG), target));
            }
            for (var step : plan.craftSteps()) tasks.add(new CraftRecipeTask(step));
            if (tasks.isEmpty()) return ActionResult.failed(ActionResult.Failure.WORLD_CHANGED);
            production = new TaskSequence("wayside:material_production", tasks);
        }
        ActionResult result = production.tick(npc);
        return result.isSuccess() && !isSatisfied(npc) ? ActionResult.failed(ActionResult.Failure.WORLD_CHANGED) : result;
    }

    private boolean isSatisfied(NpcEntity npc) {
        return requested.entrySet().stream().allMatch(entry ->
                InventoryOps.count(npc.getInventory(), entry.getKey()) >= entry.getValue());
    }

    @Override public void pause(NpcEntity npc) {
        super.pause(npc);
        // 已完成的操作已经反映在库存中。恢复时重新规划，避免重复执行部分完成的配方。
        production = null;
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
        production = null;
    }
}
