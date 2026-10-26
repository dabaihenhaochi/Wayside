package io.github.dabaihenhaochi.wayside.material;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * An immutable crafting plan. It is a calculation only: no items have been
 * reserved, gathered or consumed in the world.
 */
public record MaterialPlan(
        List<CraftStep> craftSteps,
        Map<Item, Integer> requiredSupplies,
        Map<Item, Integer> unavailable
) {
    public MaterialPlan {
        craftSteps = List.copyOf(craftSteps);
        requiredSupplies = Collections.unmodifiableMap(new LinkedHashMap<>(requiredSupplies));
        unavailable = Collections.unmodifiableMap(new LinkedHashMap<>(unavailable));
    }

    public boolean isFeasible() {
        return unavailable.isEmpty();
    }

    /**
     * The selected concrete ingredient for every slot in a single craft.
     * Empty shaped-recipe slots are represented by Items.AIR.
     * 'crafts' is the number of executions, not the number of output items.
     */
    public record CraftStep(
            ResourceLocation recipeId,
            Item output,
            int outputPerCraft,
            int crafts,
            int width,
            int height,
            List<Item> ingredients
    ) {
        public CraftStep {
            Objects.requireNonNull(recipeId);
            Objects.requireNonNull(output);
            ingredients = List.copyOf(ingredients);
            if (output == Items.AIR || outputPerCraft <= 0 || crafts <= 0
                    || width < 1 || width > 3 || height < 1 || height > 3
                    || ingredients.size() != width * height) {
                throw new IllegalArgumentException("Invalid crafting step");
            }
        }
    }
}
