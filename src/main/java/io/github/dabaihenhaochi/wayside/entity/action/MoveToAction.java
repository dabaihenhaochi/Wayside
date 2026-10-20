package io.github.dabaihenhaochi.wayside.entity.action;

import io.github.dabaihenhaochi.wayside.entity.NpcEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;

import java.util.Objects;
import java.util.function.Predicate;

/** Generic movement to a position or a live entity; no resource-specific policy. */
public final class MoveToAction implements NpcAction {
    private static final long TIMEOUT_TICKS = 600;
    private static final long RETRY_TICKS = 20;

    private final BlockPos positionTarget;
    private final Entity entityTarget;
    private final double speed;
    private final Predicate<NpcEntity> reached;

    private long startTime = -1;
    private long nextRetryTime;

    public MoveToAction(BlockPos position, double speed) {
        this(position, speed, npc -> {
            double dx = npc.getX() - (position.getX() + 0.5);
            double dy = npc.getY() - position.getY();
            double dz = npc.getZ() - (position.getZ() + 0.5);
            return dx * dx + dy * dy + dz * dz <= 2.25;
        });
    }

    public MoveToAction(BlockPos position, double speed, Predicate<NpcEntity> reached) {
        this.positionTarget = Objects.requireNonNull(position).immutable();
        this.entityTarget = null;
        this.speed = speed;
        this.reached = Objects.requireNonNull(reached);
    }

    public MoveToAction(Entity entity, double speed, Predicate<NpcEntity> reached) {
        this.positionTarget = null;
        this.entityTarget = Objects.requireNonNull(entity);
        this.speed = speed;
        this.reached = Objects.requireNonNull(reached);
    }

    public BlockPos targetPosition() {
        return entityTarget != null ? entityTarget.blockPosition() : positionTarget;
    }

    @Override
    public ActionResult tick(NpcEntity npc) {
        if (entityTarget != null && !entityTarget.isAlive()) {
            return ActionResult.failed(ActionResult.Failure.INVALID_TARGET);
        }

        if (reached.test(npc)) {
            npc.getNavigation().stop();
            return ActionResult.success();
        }

        long now = npc.level().getGameTime();
        if (startTime < 0) {
            startTime = now;
            nextRetryTime = 0;
        }

        if (now - startTime >= TIMEOUT_TICKS) {
            return ActionResult.failed(ActionResult.Failure.TIMED_OUT);
        }

        if (now >= nextRetryTime && npc.getNavigation().isDone()) {
            if (entityTarget != null) {
                npc.getNavigation().moveTo(entityTarget, speed);
            } else {
                npc.getNavigation().moveTo(
                        positionTarget.getX() + 0.5,
                        positionTarget.getY(),
                        positionTarget.getZ() + 0.5,
                        0, speed
                );
            }
            nextRetryTime = now + RETRY_TICKS;
        }

        return ActionResult.running();
    }

    @Override
    public void stop(NpcEntity npc) {
        npc.getNavigation().stop();
    }
}
