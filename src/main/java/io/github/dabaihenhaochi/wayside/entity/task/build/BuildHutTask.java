package io.github.dabaihenhaochi.wayside.entity.task.build;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Rotation;

/** 小屋只是通用建造流程的一组参数，也可以作为更高层任务链的一个步骤。 */
public final class BuildHutTask extends BuildBuildingTask {
    public static final String ID = "wayside:build_hut";
    static final ResourceLocation BLUEPRINT = ResourceLocation.fromNamespaceAndPath("wayside", "test_house");

    public BuildHutTask() {
        super(ID, BLUEPRINT, Rotation.NONE, 8, 2, 2);
    }
}
