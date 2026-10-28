package io.github.dabaihenhaochi.wayside.entity.action;

import io.github.dabaihenhaochi.wayside.entity.NpcEntity;
import io.github.dabaihenhaochi.wayside.entity.ai.NpcBlockInteraction;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.EventHooks;

import java.util.Objects;
import java.util.function.Predicate;

// 持续破坏调用方允许的目标方块。
public final class BreakBlockAction implements NpcAction {
    private static final long WORK_TICKS = 40;
    private static final long SWING_TICKS = 10;

    private final BlockPos pos;
    private final Predicate<BlockState> allowedTarget;
    private BlockState startingState;
    private long startedAt = -1;
    private long nextSwing;
    private int lastProgress = -1;

    public BreakBlockAction(BlockPos pos, Predicate<BlockState> allowedTarget) {
        this.pos = Objects.requireNonNull(pos).immutable();
        this.allowedTarget = Objects.requireNonNull(allowedTarget);
    }

    // 持续校验目标、权限和可达性，更新破坏进度，到时生成正常掉落。
    @Override
    public ActionResult tick(NpcEntity npc) {
        if (!npc.level().hasChunkAt(pos)) {
            return ActionResult.failed(ActionResult.Failure.INVALID_TARGET);
        }

        BlockState state = npc.level().getBlockState(pos);
        if (state.getDestroySpeed(npc.level(), pos) < 0) {
            return ActionResult.failed(ActionResult.Failure.FORBIDDEN);
        }
        if (!allowedTarget.test(state)) {
            return ActionResult.failed(ActionResult.Failure.INVALID_TARGET);
        }
        if (startingState != null && !startingState.equals(state)) {
            return ActionResult.failed(ActionResult.Failure.WORLD_CHANGED);
        }
        if (!EventHooks.canEntityGrief(npc.level(), npc)) {
            return ActionResult.failed(ActionResult.Failure.FORBIDDEN);
        }
        if (!NpcBlockInteraction.canWorkAt(npc, pos, npc.position())) {
            return ActionResult.failed(ActionResult.Failure.UNREACHABLE);
        }

        long now = npc.level().getGameTime();
        if (startedAt < 0) {
            startingState = state;
            startedAt = now;
            nextSwing = now;
            npc.getNavigation().stop();
        }

        Vec3 center = Vec3.atCenterOf(pos);
        npc.getLookControl().setLookAt(center.x, center.y, center.z);

        if (now >= nextSwing) {
            npc.swing(InteractionHand.MAIN_HAND);
            nextSwing = now + SWING_TICKS;
        }

        long elapsed = now - startedAt;
        int progress = (int) Math.min(9L, elapsed * 10L / WORK_TICKS);
        if (lastProgress != progress) {
            npc.level().destroyBlockProgress(npc.getId(), pos, progress);
            lastProgress = progress;
        }

        if (elapsed < WORK_TICKS) {
            return ActionResult.running();
        }

        boolean allowed = state.canEntityDestroy(npc.level(), pos, npc)
                && EventHooks.onEntityDestroyBlock(npc, pos, state);
        if (!allowed) {
            return ActionResult.failed(ActionResult.Failure.FORBIDDEN);
        }
        if (!npc.level().destroyBlock(pos, true, npc)) {
            return ActionResult.failed(ActionResult.Failure.WORLD_CHANGED);
        }
        return ActionResult.success();
    }

    // 结束或中断时清除方块裂纹。
    @Override
    public void stop(NpcEntity npc) {
        npc.level().destroyBlockProgress(npc.getId(), pos, -1);
        lastProgress = -1;
    }
}
