package io.github.dabaihenhaochi.wayside.material;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapelessRecipe;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Plans only ordinary crafting-table recipes (up to 3x3).
 * A separate execution task will later acquire supplies and perform crafts.
 * No smelting, special recipes or recipes with crafting remainders.
 */
public final class MaterialPlanner {
    private static final int MAX_DEPTH = 8;
    private static final int MAX_RECIPE_OPTIONS = 24;
    private static final int MAX_INGREDIENT_OPTIONS = 24;
    private static final int MAX_STEPS = 128;

    private MaterialPlanner() {}

    /**
     * @param requested exact output items and target counts
     * @param inventory snapshot of currently available items; never modified
     * @param suppliedItems items that an external source may provide later
     */
    public static MaterialPlan plan(
            ServerLevel level,
            Map<Item, Integer> requested,
            SimpleContainer inventory,
            Set<Item> suppliedItems
    ) {
        Objects.requireNonNull(level);
        Objects.requireNonNull(requested);
        Objects.requireNonNull(inventory);
        Objects.requireNonNull(suppliedItems);

        State state = new State();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty()) {
                state.available.merge(stack.getItem(), stack.getCount(), Integer::sum);
            }
        }

        Map<Item, List<RecipeHolder<CraftingRecipe>>> recipes = new HashMap<>();
        for (RecipeHolder<CraftingRecipe> holder
                : level.getRecipeManager().getAllRecipesFor(RecipeType.CRAFTING)) {
            CraftingRecipe recipe = holder.value();
            if (recipe.isSpecial() || recipe.isIncomplete()
                    || !recipe.canCraftInDimensions(3, 3)
                    || !(recipe instanceof ShapedRecipe || recipe instanceof ShapelessRecipe)) {
                continue;
            }
            ItemStack output = recipe.getResultItem(level.registryAccess());
            if (!output.isEmpty()) {
                recipes.computeIfAbsent(output.getItem(), unused -> new ArrayList<>())
                        .add(holder);
            }
        }
        recipes.values().forEach(list ->
                list.sort(Comparator.comparing(holder -> holder.id().toString())));

        Set<Item> allowedSupplies = Set.copyOf(suppliedItems);
        for (Map.Entry<Item, Integer> request : requested.entrySet()) {
            Item item = Objects.requireNonNull(request.getKey());
            int count = Objects.requireNonNull(request.getValue());
            if (item == Items.AIR || count <= 0) {
                throw new IllegalArgumentException("Requested amounts must be positive items");
            }
            fulfill(item, count, state, recipes, allowedSupplies, level.registryAccess(), Set.of(), 0);
        }
        return new MaterialPlan(state.steps, state.supplies, state.unavailable);
    }

    private static boolean fulfill(
            Item item, int count, State state,
            Map<Item, List<RecipeHolder<CraftingRecipe>>> recipes,
            Set<Item> supplies, net.minecraft.core.HolderLookup.Provider registries,
            Set<Item> ancestors, int depth
    ) {
        int stocked = state.available.getOrDefault(item, 0);
        int taken = Math.min(stocked, count);
        if (taken > 0) {
            state.available.put(item, stocked - taken);
        }
        int missing = count - taken;
        if (missing == 0) return true;

        if (supplies.contains(item)) {
            state.supplies.merge(item, missing, Integer::sum);
            return true;
        }

        if (depth >= MAX_DEPTH || ancestors.contains(item) || state.steps.size() >= MAX_STEPS) {
            state.unavailable.merge(item, missing, Integer::sum);
            return false;
        }

        Set<Item> chain = new HashSet<>(ancestors);
        chain.add(item);
        int tried = 0;
        for (RecipeHolder<CraftingRecipe> holder : recipes.getOrDefault(item, List.of())) {
            if (++tried > MAX_RECIPE_OPTIONS) break;
            CraftingRecipe recipe = holder.value();
            ItemStack output = recipe.getResultItem(registries);
            if (output.getCount() <= 0) continue;
            int times = (missing + output.getCount() - 1) / output.getCount();
            List<Ingredient> ingredients = recipe.getIngredients();
            if (ingredients.isEmpty() || ingredients.size() > 9) continue;

            int width;
            int height;
            if (recipe instanceof ShapedRecipe shaped) {
                width = shaped.getWidth();
                height = shaped.getHeight();
            } else {
                width = Math.min(3, ingredients.size());
                height = (ingredients.size() + width - 1) / width;
            }
            if (width <= 0 || height <= 0 || width > 3 || height > 3
                    || ingredients.size() > width * height) continue;

            State candidate = state.copy();
            Resolution resolved = resolveIngredients(
                    0, ingredients, times, candidate, recipes, supplies, registries, chain, depth + 1,
                    new ArrayList<>());
            if (resolved == null || resolved.state.steps.size() >= MAX_STEPS) continue;

            List<Item> slots = new ArrayList<>(resolved.slots);
            while (slots.size() < width * height) slots.add(Items.AIR);
            resolved.state.steps.add(new MaterialPlan.CraftStep(
                    holder.id(), item, output.getCount(), times, width, height, slots));
            int surplus = times * output.getCount() - missing;
            if (surplus > 0) {
                resolved.state.available.merge(item, surplus, Integer::sum);
            }
            state.replace(resolved.state);
            return true;
        }

        state.unavailable.merge(item, missing, Integer::sum);
        return false;
    }

    private static Resolution resolveIngredients(
            int index, List<Ingredient> ingredients, int times, State state,
            Map<Item, List<RecipeHolder<CraftingRecipe>>> recipes,
            Set<Item> supplies, net.minecraft.core.HolderLookup.Provider registries,
            Set<Item> ancestors, int depth, List<Item> slots
    ) {
        if (index == ingredients.size()) return new Resolution(state, slots);

        Ingredient ingredient = ingredients.get(index);
        if (ingredient.isEmpty()) {
            List<Item> next = new ArrayList<>(slots);
            next.add(Items.AIR);
            return resolveIngredients(index + 1, ingredients, times, state, recipes,
                    supplies, registries, ancestors, depth, next);
        }

        List<Item> candidates = java.util.Arrays.stream(ingredient.getItems())
                .filter(stack -> !stack.isEmpty()
                        && !stack.hasCraftingRemainingItem())
                .map(ItemStack::getItem)
                .distinct()
                .filter(item -> ingredient.test(new ItemStack(item)))
                .sorted(Comparator
                        .comparingInt((Item item) ->
                                state.available.getOrDefault(item, 0) > 0 ? 0
                                : supplies.contains(item) ? 1
                                : recipes.containsKey(item) ? 2 : 3)
                        .thenComparing(item -> net.minecraft.core.registries.BuiltInRegistries.ITEM
                                .getKey(item).toString()))
                .limit(MAX_INGREDIENT_OPTIONS)
                .toList();

        for (Item option : candidates) {
            State branch = state.copy();
            if (!fulfill(option, times, branch, recipes, supplies, registries, ancestors, depth)) continue;
            List<Item> next = new ArrayList<>(slots);
            next.add(option);
            Resolution found = resolveIngredients(index + 1, ingredients, times, branch,
                    recipes, supplies, registries, ancestors, depth, next);
            if (found != null) return found;
        }
        return null;
    }

    private record Resolution(State state, List<Item> slots) {}

    private static final class State {
        private final Map<Item, Integer> available = new LinkedHashMap<>();
        private final Map<Item, Integer> supplies = new LinkedHashMap<>();
        private final Map<Item, Integer> unavailable = new LinkedHashMap<>();
        private final List<MaterialPlan.CraftStep> steps = new ArrayList<>();

        State copy() {
            State clone = new State();
            clone.available.putAll(available);
            clone.supplies.putAll(supplies);
            clone.unavailable.putAll(unavailable);
            clone.steps.addAll(steps);
            return clone;
        }

        void replace(State next) {
            available.clear();
            available.putAll(next.available);
            supplies.clear();
            supplies.putAll(next.supplies);
            unavailable.clear();
            unavailable.putAll(next.unavailable);
            steps.clear();
            steps.addAll(next.steps);
        }
    }
}
