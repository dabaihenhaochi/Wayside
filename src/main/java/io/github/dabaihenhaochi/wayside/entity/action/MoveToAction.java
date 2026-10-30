package io.github.dabaihenhaochi.wayside.entity.action;

import io.github.dabaihenhaochi.wayside.entity.NpcEntity;
import io.github.dabaihenhaochi.wayside.entity.ai.NpcMovementSafety;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.Objects;
import java.util.function.Predicate;

// 移动到固定位置或跟随实体，由到达条件决定何时结束。
public final class MoveToAction implements NpcAction {
    private static final long TIMEOUT_TICKS = 600;
    private static final long RETRY_TICKS = 20;

    private final BlockPos positionTarget;
    private final Entity entityTarget;
    private final double speed;
    private final Predicate<NpcEntity> reached;

    private long startTime = -1;
    private long nextRetryTime;

    // 默认到达范围为距目标格底部中心 1.5 格以内。
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

    // 检查目标和超时；导航结束但尚未到达时，按间隔重新寻路。
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
            var path = npc.getNavigation().createPath(targetPosition(), 0);
            if (!NpcMovementSafety.isSafePath(path)) return ActionResult.failed(ActionResult.Failure.UNREACHABLE);
            npc.getNavigation().moveTo(path, speed);
            nextRetryTime = now + RETRY_TICKS;
        }

        // 导航可能在格子中心前约半个身位结束。对要求精确站位的调用者，
        // 继续用正常移动控制走完最后一小段，避免身体仍占据待放置方块。
        if (positionTarget != null && npc.getNavigation().isDone()) {
            Vec3 feet = Vec3.atBottomCenterOf(positionTarget);
            if (npc.position().distanceToSqr(feet) < 2.25
                    && Math.abs(npc.getY() - feet.y) < 0.5
                    && NpcMovementSafety.canWalkDirectly(npc, feet)) {
                npc.getMoveControl().setWantedPosition(feet.x, feet.y, feet.z, speed);
            }
        }
        return ActionResult.running();
    }

    @Override
    public void stop(NpcEntity npc) {
        npc.getNavigation().stop();
    }
}
