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

/**
 * Executes a material plan using the NPC's own inventory during early survival.
 * For now, the only producible base resource is an oak log gathered in-world.
 * A settlement storage/production provider can replace that source later.
 *
 * The plan is not persisted. On interruption or reload it is rebuilt from
 * the actual inventory, so partially completed crafting is never replayed.
 */
public final class SupplyMaterialsTask implements NpcTask {
    public static final String ID = "wayside:supply_materials";
    private final Map<Item, Integer> requested = new LinkedHashMap<>();

    private MaterialPlan plan;
    private int craftStepIndex;
    private CraftRecipeAction craftAction;
    private GatherResourceTask gathering;
    private int gatherTarget;

    /** Factory constructor for task deserialization. */
    public SupplyMaterialsTask() {
        this(Map.of(Items.OAK_STAIRS, 8));
    }

    public SupplyMaterialsTask(Map<Item, Integer> requested) {
        setRequested(requested);
    }

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
            // Fix the collection target at planning time. It must not grow
            // each time a log enters the inventory.
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
        // Do not silently claim arbitrary materials can be produced.
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
            // The requirement was already accounted for in the plan. The
            // gathering target is fixed until the next replanning event.
            ActionResult gathered = gathering.tick(npc);
            if (gathered.isFailed()) return gathered;
            if (!gathered.isSuccess()) return ActionResult.running();

            gathering.pause(npc);
            gathering = null;
            gatherTarget = 0;
            // Remove supplied items from the original plan once acquired:
            // future ticks must not recreate the gathering child.
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

        // A plan is calculated from an inventory snapshot; verify reality.
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
        // The stored inventory reflects every committed craft.
        // Recompute the plan on resume/retry rather than replaying those crafts.
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
