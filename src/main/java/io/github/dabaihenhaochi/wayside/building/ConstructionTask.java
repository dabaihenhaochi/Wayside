
package io.github.dabaihenhaochi.wayside.building;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Objects;

public record ConstructionTask(
        BlockPos pos,
        BlockState targetState,
        Action action
) {
    public enum Action {
        PLACE,
        REMOVE,
        REPLACE
    }

    public ConstructionTask {
        Objects.requireNonNull(pos);
        Objects.requireNonNull(targetState);
        Objects.requireNonNull(action);

        pos = pos.immutable();
    }
}
