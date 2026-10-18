package io.github.dabaihenhaochi.wayside.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import io.github.dabaihenhaochi.wayside.entity.ai.NpcMoveToTargetGoal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import io.github.dabaihenhaochi.wayside.entity.ai.NpcFindBlockGoal;
import io.github.dabaihenhaochi.wayside.entity.ai.NpcHarvestBlockGoal;
import io.github.dabaihenhaochi.wayside.entity.ai.NpcBlockInteraction;
import net.minecraft.tags.BlockTags;
import java.util.Map;
import java.util.HashMap;
import java.util.Optional;

public class NpcEntity extends PathfinderMob {
    private BlockPos moveTarget;
    private BlockPos blockTarget;

    private static final long FAILED_TARGET_COOLDOWN_TICKS = 1200L;
    private final Map<BlockPos, Long> failedBlockTargets = new HashMap<>();

    public NpcEntity(EntityType<? extends PathfinderMob> entityType, Level level) {
        super(entityType, level);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return PathfinderMob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 20.0)
                .add(Attributes.MOVEMENT_SPEED, 0.25)
                .add(Attributes.FOLLOW_RANGE, 32.0);
    }

    @Override
    protected void registerGoals() {

        this.goalSelector.addGoal(
                0,
                new NpcHarvestBlockGoal(this)
        );

        this.goalSelector.addGoal(
                1,
                new NpcMoveToTargetGoal(this, 1.0)
        );

        this.goalSelector.addGoal(
                2,
                new NpcFindBlockGoal(
                        this,
                        state -> state.is(BlockTags.LOGS)
                )
        );

        this.goalSelector.addGoal(
                3,
                new LookAtPlayerGoal(this, Player.class, 8.0F)
        );

        this.goalSelector.addGoal(
                4,
                new RandomLookAroundGoal(this)
        );
    }

    @Override
    protected InteractionResult mobInteract(Player player, InteractionHand hand) {
        if (!this.level().isClientSide) {
            this.clearBlockTarget();
            this.setMoveTarget(player.blockPosition());
        }

        return InteractionResult.sidedSuccess(this.level().isClientSide);
    }

    public void setMoveTarget(BlockPos moveTarget) {
        // 外部移动命令取代采集任务；自动站位由 planMoveToBlockTarget 单独设置。
        this.clearBlockTarget();
        this.moveTarget = moveTarget.immutable();
    }

    public BlockPos getMoveTarget() {
        return this.moveTarget;
    }

    public boolean hasMoveTarget() {
        return this.moveTarget != null;
    }

    public void clearMoveTarget() {
        this.moveTarget = null;
    }

    public void setBlockTarget(BlockPos blockTarget) {
        this.clearBlockTarget();
        this.blockTarget = blockTarget.immutable();
    }

    public BlockPos getBlockTarget() {
        return this.blockTarget;
    }

    public boolean hasBlockTarget() {
        return this.blockTarget != null;
    }

    public void clearBlockTarget() {
        if (this.blockTarget != null) {
            this.clearMoveTarget();
        }
        this.blockTarget = null;
    }

    // 移动和采集共用操作距离、遮挡和落脚处检查。
    public boolean canReachBlock(BlockPos target) {
        return NpcBlockInteraction.canWorkAt(this, target, this.position());
    }

    public boolean planMoveToBlockTarget() {
        if (this.blockTarget == null) {
            return false;
        }

        if (this.canReachBlock(this.blockTarget)) {
            this.clearMoveTarget();
            return true;
        }

        Optional<BlockPos> workPosition = NpcBlockInteraction.findWorkPosition(this, this.blockTarget);
        if (workPosition.isEmpty()) {
            return false;
        }

        this.moveTarget = workPosition.get();
        return true;
    }

    public void markBlockTargetFailed(BlockPos pos) {
        long now = this.level().getGameTime();

        failedBlockTargets.entrySet().removeIf(
                entry -> entry.getValue() <= now
        );

        failedBlockTargets.put(
                pos.immutable(),
                now + FAILED_TARGET_COOLDOWN_TICKS
        );
    }

    public boolean isBlockTargetOnCooldown(BlockPos pos) {
        Long retryTime = failedBlockTargets.get(pos);

        if (retryTime == null) {
            return false;
        }

        if (this.level().getGameTime() >= retryTime) {
            failedBlockTargets.remove(pos);
            return false;
        }

        return true;
    }
}
