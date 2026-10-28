package io.github.dabaihenhaochi.wayside.entity.task.resource;

import io.github.dabaihenhaochi.wayside.entity.task.TaskSequence;

import io.github.dabaihenhaochi.wayside.entity.NpcEntity;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import java.util.List;

// 采集合成场景：先使原木库存达到 16，再将匹配原木合成为木板直至耗尽。
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

    // v0.0.6 的业务阶段在本场景中转换，不让通用任务序列认识采集和合成。
    @Override public void load(CompoundTag tag) {
        if (!tag.contains("Stage", Tag.TAG_STRING)) { super.load(tag); return; }
        CompoundTag migrated = new CompoundTag();
        migrated.putInt("Current", switch (tag.getString("Stage")) {
            case "CRAFTING" -> 1;
            case "COMPLETED" -> 2;
            default -> 0;
        });
        super.load(migrated);
    }
}
