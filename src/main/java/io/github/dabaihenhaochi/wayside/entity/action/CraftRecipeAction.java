package io.github.dabaihenhaochi.wayside.entity.action;

import io.github.dabaihenhaochi.wayside.entity.NpcEntity;
import io.github.dabaihenhaochi.wayside.entity.inventory.InventoryOps;
import io.github.dabaihenhaochi.wayside.material.MaterialPlan;
import net.minecraft.core.BlockPos;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

// 执行计划指定的配方；每次先模拟库存，全部检查通过后才提交。
public final class CraftRecipeAction implements NpcAction {
    private static final long INTERVAL_TICKS = 20;
    private static final int WORKBENCH_RANGE = 3;
    private final MaterialPlan.CraftStep step;
    private int completedCrafts;
    private long nextCraftTime = -1;

    public CraftRecipeAction(MaterialPlan.CraftStep step) {
        this.step = Objects.requireNonNull(step);
    }

    // 按间隔执行计划次数，核验工作台、原料和产物，失败时保留本次合成前的库存。
    @Override
    public ActionResult tick(NpcEntity npc) {
        if (completedCrafts >= step.crafts()) {
            return ActionResult.success();
        }

        if ((step.width() > 2 || step.height() > 2) && !hasWorkstation(npc)) {
            return ActionResult.failed(ActionResult.Failure.MISSING_WORKSTATION);
        }

        long now = npc.level().getGameTime();
        if (nextCraftTime < 0) nextCraftTime = now + INTERVAL_TICKS;
        if (now < nextCraftTime) return ActionResult.running();
        nextCraftTime = now + INTERVAL_TICKS;

        RecipeHolder<CraftingRecipe> recipeHolder = npc.level().getRecipeManager()
                .getAllRecipesFor(RecipeType.CRAFTING).stream()
                .filter(holder -> holder.id().equals(step.recipeId()))
                .findFirst().orElse(null);
        if (recipeHolder == null || recipeHolder.value().isSpecial()
                || recipeHolder.value().isIncomplete()) {
            return ActionResult.failed(ActionResult.Failure.NO_RECIPE);
        }

        SimpleContainer original = npc.getInventory();
        SimpleContainer simulated = InventoryOps.copy(original);
        List<ItemStack> inputs = new ArrayList<>(step.ingredients().size());

        for (Item required : step.ingredients()) {
            if (required == Items.AIR) {
                inputs.add(ItemStack.EMPTY);
                continue;
            }
            int slot = findStack(simulated, required);
            if (slot < 0) {
                return ActionResult.failed(ActionResult.Failure.MISSING_INGREDIENT);
            }
            ItemStack chosen = simulated.getItem(slot).copyWithCount(1);
            // 暂不处理桶等合成后返还物。
            if (chosen.hasCraftingRemainingItem()) {
                return ActionResult.failed(ActionResult.Failure.NO_RECIPE);
            }
            inputs.add(chosen);
            simulated.removeItem(slot, 1);
        }

        CraftingInput input = CraftingInput.of(step.width(), step.height(), inputs);
        CraftingRecipe recipe = recipeHolder.value();
        if (!recipe.matches(input, npc.level())) {
            return ActionResult.failed(ActionResult.Failure.NO_RECIPE);
        }
        ItemStack output = recipe.assemble(input, npc.level().registryAccess());
        if (output.isEmpty() || output.getItem() != step.output()
                || output.getCount() != step.outputPerCraft()) {
            return ActionResult.failed(ActionResult.Failure.WORLD_CHANGED);
        }

        if (!simulated.addItem(output.copy()).isEmpty()) {
            return ActionResult.failed(ActionResult.Failure.NO_SPACE);
        }
        // 确认原料和产物均有效且能装入背包后，一次性提交本次合成。
        InventoryOps.replaceContents(original, simulated);

        completedCrafts++;
        return completedCrafts >= step.crafts()
                ? ActionResult.success() : ActionResult.running();
    }

    private static int findStack(SimpleContainer inventory, Item required) {
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty() && stack.is(required)) return i;
        }
        return -1;
    }

    // 超出 2×2 的配方暂以附近存在已加载的工作台为条件。
    public static boolean hasWorkstation(NpcEntity npc) {
        BlockPos center = npc.blockPosition();
        for (int x = -WORKBENCH_RANGE; x <= WORKBENCH_RANGE; x++) {
            for (int y = -2; y <= 2; y++) {
                for (int z = -WORKBENCH_RANGE; z <= WORKBENCH_RANGE; z++) {
                    BlockPos pos = center.offset(x, y, z);
                    if (npc.level().hasChunkAt(pos)
                            && npc.level().getBlockState(pos).is(Blocks.CRAFTING_TABLE)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    public int completedCrafts() {
        return completedCrafts;
    }
}
