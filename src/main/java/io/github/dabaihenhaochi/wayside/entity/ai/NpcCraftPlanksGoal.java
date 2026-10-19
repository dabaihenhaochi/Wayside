
package io.github.dabaihenhaochi.wayside.entity.ai;

import io.github.dabaihenhaochi.wayside.entity.NpcEntity;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.RecipeType;

import java.util.EnumSet;
import java.util.List;

public class NpcCraftPlanksGoal extends Goal {

    private static final int CRAFT_INTERVAL_TICKS = 20;

    private final NpcEntity npc;
    private long nextCraftTime;

    public NpcCraftPlanksGoal(NpcEntity npc) {
        this.npc = npc;
        this.setFlags(EnumSet.of(Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        return !npc.level().isClientSide
                && npc.getTaskStage() == NpcEntity.TaskStage.CRAFTING
                && !npc.hasMoveTarget();
    }

    @Override
    public boolean canContinueToUse() {
        return canUse();
    }

    @Override
    public void start() {
        nextCraftTime = npc.level().getGameTime()
                + CRAFT_INTERVAL_TICKS;
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        long now = npc.level().getGameTime();

        if (now < nextCraftTime) {
            return;
        }

        nextCraftTime = now + CRAFT_INTERVAL_TICKS;

        if (npc.getLogCount() == 0) {
            npc.finishCrafting();
            return;
        }

        SimpleContainer inventory = npc.getInventory();

        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack log = inventory.getItem(i);

            if (!log.is(ItemTags.LOGS)) {
                continue;
            }

            CraftingInput input = CraftingInput.of(
                    1, 1, List.of(log.copyWithCount(1))
            );

            var recipe = npc.level().getRecipeManager()
                    .getRecipeFor(
                            RecipeType.CRAFTING,
                            input,
                            npc.level()
                    );

            if (recipe.isEmpty()) {
                continue;
            }

            ItemStack result = recipe.get().value().assemble(
                    input,
                    npc.level().registryAccess()
            );

            if (result.isEmpty() || !result.is(ItemTags.PLANKS)) {
                continue;
            }

            // 先模拟操作，检查合成结果能否完整放入背包。
            SimpleContainer simulated =
                    new SimpleContainer(inventory.getContainerSize());

            for (int j = 0; j < inventory.getContainerSize(); j++) {
                simulated.setItem(j, inventory.getItem(j).copy());
            }

            simulated.removeItem(i, 1);

            if (!simulated.addItem(result.copy()).isEmpty()) {
                continue;
            }

            // 模拟成功后，才正式消耗原木并加入木板。
            inventory.removeItem(i, 1);
            inventory.addItem(result.copy());

            if (npc.getLogCount() == 0) {
                npc.finishCrafting();
            }

            return;
        }
    }
}
