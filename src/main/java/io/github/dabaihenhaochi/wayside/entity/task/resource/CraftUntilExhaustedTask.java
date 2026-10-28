package io.github.dabaihenhaochi.wayside.entity.task.resource;

import io.github.dabaihenhaochi.wayside.entity.task.NpcTask;

import io.github.dabaihenhaochi.wayside.entity.NpcEntity;
import io.github.dabaihenhaochi.wayside.entity.action.ActionResult;
import io.github.dabaihenhaochi.wayside.entity.action.CraftSingleIngredientAction;
import net.minecraft.world.item.ItemStack;
import java.util.Objects;
import java.util.function.Predicate;

// 持续执行 1×1 合成，直到匹配原料耗尽。
public final class CraftUntilExhaustedTask implements NpcTask {
    private final Predicate<ItemStack> ingredient;
    private final Predicate<ItemStack> product;
    private CraftSingleIngredientAction action;

    public CraftUntilExhaustedTask(Predicate<ItemStack> ingredient, Predicate<ItemStack> product) {
        this.ingredient = Objects.requireNonNull(ingredient);
        this.product = Objects.requireNonNull(product);
    }

    @Override public String typeId() { return "wayside:craft_until_exhausted"; }
    @Override public String stageName() { return "CRAFTING"; }
    @Override public ActionResult tick(NpcEntity npc) {
        if (action == null) action = new CraftSingleIngredientAction(ingredient, product);
        return action.tick(npc);
    }
    // 释放临时合成动作，继续时重新创建。
    @Override public void pause(NpcEntity npc) {
        if (action != null) {
            action.stop(npc);
            action = null;
        }
    }
}
