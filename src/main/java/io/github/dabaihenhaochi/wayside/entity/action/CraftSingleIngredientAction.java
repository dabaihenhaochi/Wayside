package io.github.dabaihenhaochi.wayside.entity.action;

import io.github.dabaihenhaochi.wayside.entity.NpcEntity;
import io.github.dabaihenhaochi.wayside.entity.inventory.InventoryOps;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.RecipeType;

import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

// 使用原版 1×1 配方持续合成匹配的原料。
public final class CraftSingleIngredientAction implements NpcAction {
    private static final long INTERVAL_TICKS = 20;

    private final Predicate<ItemStack> ingredient;
    private final Predicate<ItemStack> output;
    private long nextCraftTime = -1;

    public CraftSingleIngredientAction(
            Predicate<ItemStack> ingredient,
            Predicate<ItemStack> output
    ) {
        this.ingredient = Objects.requireNonNull(ingredient);
        this.output = Objects.requireNonNull(output);
    }

    // 按间隔尝试配方；原料耗尽则成功，有原料却无匹配产物则失败。
    @Override
    public ActionResult tick(NpcEntity npc) {
        long now = npc.level().getGameTime();
        if (nextCraftTime < 0) {
            nextCraftTime = now + INTERVAL_TICKS;
        }
        if (now < nextCraftTime) {
            return ActionResult.running();
        }
        nextCraftTime = now + INTERVAL_TICKS;

        SimpleContainer inventory = npc.getInventory();
        boolean foundIngredient = false;

        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack inputStack = inventory.getItem(i);
            if (!ingredient.test(inputStack)) {
                continue;
            }
            foundIngredient = true;
            CraftingInput input = CraftingInput.of(
                    1, 1, List.of(inputStack.copyWithCount(1)));

            var recipe = npc.level().getRecipeManager().getRecipeFor(
                    RecipeType.CRAFTING, input, npc.level());
            if (recipe.isEmpty()) {
                continue;
            }

            ItemStack product = recipe.get().value().assemble(
                    input, npc.level().registryAccess());
            if (product.isEmpty() || !output.test(product)) {
                continue;
            }

            // 先模拟扣料和装入产物，避免背包满时损失原料。
            if (!InventoryOps.exchange(inventory, i, 1, product)) {
                return ActionResult.failed(ActionResult.Failure.NO_SPACE);
            }

            return ActionResult.running();
        }

        return foundIngredient
                ? ActionResult.failed(ActionResult.Failure.NO_RECIPE)
                : ActionResult.success();
    }
}
