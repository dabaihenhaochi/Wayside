package io.github.dabaihenhaochi.wayside.entity.ai;

import io.github.dabaihenhaochi.wayside.entity.NpcEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;

/** 导航与末端站位修正共用的落脚规则。 */
public final class NpcMovementSafety {
    public static final int MAX_DROP = 1;
    private NpcMovementSafety() {}

    public static boolean isSafePath(Path path) {
        if (path == null || !path.canReach()) return false;
        for (int i = Math.max(1, path.getNextNodeIndex()); i < path.getNodeCount(); i++) {
            if (path.getNode(i - 1).y - path.getNode(i).y > MAX_DROP) return false;
        }
        return true;
    }

    public static boolean canWalkDirectly(NpcEntity npc, Vec3 destination) {
        if (!npc.onGround() || Math.abs(destination.y - npc.getY()) > 0.05) return false;
        Vec3 start = npc.position();
        int samples = Math.max(1, (int) Math.ceil(start.distanceTo(destination) / 0.15));
        for (int i = 0; i <= samples; i++) {
            Vec3 feet = start.lerp(destination, (double) i / samples);
            var body = npc.getDimensions(npc.getPose()).makeBoundingBox(feet);
            BlockPos support = BlockPos.containing(feet.x, feet.y - 0.01, feet.z);
            if (!npc.level().hasChunksAt(support, BlockPos.containing(feet.x, body.maxY, feet.z))
                    || !npc.level().getFluidState(support).isEmpty()
                    || !npc.level().getBlockState(support).isFaceSturdy(npc.level(), support, Direction.UP)
                    || npc.level().containsAnyLiquid(body) || !npc.level().noCollision(npc, body)) return false;
        }
        return true;
    }
}
