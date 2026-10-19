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
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.item.ItemEntity;
import io.github.dabaihenhaochi.wayside.entity.ai.NpcCollectItemGoal;
import net.minecraft.tags.ItemTags;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.ContainerHelper;

public class NpcEntity extends PathfinderMob {
    private BlockPos moveTarget;
    private BlockPos blockTarget;

    private static final long FAILED_TARGET_COOLDOWN_TICKS = 1200L;
    private final Map<BlockPos, Long> failedBlockTargets = new HashMap<>();

    private final SimpleContainer inventory = new SimpleContainer(9);
    private static final int TARGET_LOG_COUNT = 16;

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
                new NpcCollectItemGoal(this)
        );

        this.goalSelector.addGoal(
                3,
                new NpcFindBlockGoal(
                        this,
                        state -> state.is(BlockTags.LOGS)
                )
        );

        this.goalSelector.addGoal(
                4,
                new LookAtPlayerGoal(this, Player.class, 8.0F)
        );

        this.goalSelector.addGoal(
                5,
                new RandomLookAroundGoal(this)
        );
    }

    @Override
    protected InteractionResult mobInteract(Player player, InteractionHand hand) {
        if (!this.level().isClientSide) {
            this.clearBlockTarget();
            this.setMoveTarget(player.blockPosition());

            player.sendSystemMessage(Component.literal(
                    "原木数量：" + this.getLogCount()
                            + " / " + TARGET_LOG_COUNT
            ));
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

    public SimpleContainer getInventory() {
        return this.inventory;
    }

    @Override
    public void tick() {
        super.tick();

        if (this.level().isClientSide || this.tickCount % 10 != 0) {
            return;
        }

        for (ItemEntity item : this.level().getEntitiesOfClass(
                ItemEntity.class,
                this.getBoundingBox().inflate(0.5)
        )) {
            if (item.hasPickUpDelay() || !this.wantsItem(item.getItem())) {
                continue;
            }

            ItemStack stack = item.getItem();
            ItemStack remaining = this.inventory.addItem(stack.copy());

            if (remaining.isEmpty()) {
                item.discard();
            } else {
                item.setItem(remaining);
            }
        }
    }

    public int getLogCount() {
        int count = 0;

        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);

            if (stack.is(ItemTags.LOGS)) {
                count += stack.getCount();
            }
        }

        return count;
    }

    public boolean needsLogs() {
        return getLogCount() < TARGET_LOG_COUNT;
    }

    public boolean wantsItem(ItemStack stack) {
        return stack.is(ItemTags.LOGS) && this.needsLogs();
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);

        CompoundTag inventoryTag = new CompoundTag();

        ContainerHelper.saveAllItems(
                inventoryTag,
                this.inventory.getItems(),
                this.level().registryAccess()
        );

        tag.put("WaysideInventory", inventoryTag);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);

        this.inventory.clearContent();

        if (tag.contains("WaysideInventory", Tag.TAG_COMPOUND)) {
            ContainerHelper.loadAllItems(
                    tag.getCompound("WaysideInventory"),
                    this.inventory.getItems(),
                    this.level().registryAccess()
            );
        }
    }
}
