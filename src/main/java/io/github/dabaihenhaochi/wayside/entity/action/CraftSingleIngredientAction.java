package io.github.dabaihenhaochi.wayside.entity.action;

import io.github.dabaihenhaochi.wayside.entity.NpcEntity;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.RecipeType;

import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

/** Recipe-driven 1x1 crafting. Larger grids will be a separate extension. */
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

            SimpleContainer simulated = new SimpleContainer(inventory.getContainerSize());
            for (int j = 0; j < inventory.getContainerSize(); j++) {
                simulated.setItem(j, inventory.getItem(j).copy());
            }
            simulated.removeItem(i, 1);
            if (!simulated.addItem(product.copy()).isEmpty()) {
                return ActionResult.failed(ActionResult.Failure.NO_SPACE);
            }

            inventory.removeItem(i, 1);
            inventory.addItem(product.copy());
            return ActionResult.running();
        }

        return foundIngredient
                ? ActionResult.failed(ActionResult.Failure.NO_RECIPE)
                : ActionResult.success();
    }
}
