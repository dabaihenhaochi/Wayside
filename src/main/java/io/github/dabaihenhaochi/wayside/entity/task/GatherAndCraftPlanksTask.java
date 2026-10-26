package io.github.dabaihenhaochi.wayside.entity.task;

import io.github.dabaihenhaochi.wayside.entity.NpcEntity;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import java.util.List;

// 默认任务：先使原木库存达到 16，再将匹配原木合成为木板直至耗尽。
public final class GatherAndCraftPlanksTask extends TaskSequence {
    public static final String ID = "wayside:gather_and_craft_planks";

    public GatherAndCraftPlanksTask() {
        super(ID, List.of(
                new GatherResourceTask(
                        state -> state.is(BlockTags.LOGS),
                        item -> item.is(ItemTags.LOGS),
                        NpcEntity::getLogCount,
                        16
                ),
                new CraftUntilExhaustedTask(
                        item -> item.is(ItemTags.LOGS),
                        item -> item.is(ItemTags.PLANKS)
                )
        ));
    }
}
