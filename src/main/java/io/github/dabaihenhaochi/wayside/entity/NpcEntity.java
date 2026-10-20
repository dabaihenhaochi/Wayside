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
import io.github.dabaihenhaochi.wayside.entity.ai.NpcCraftPlanksGoal;
import io.github.dabaihenhaochi.wayside.building.BuildingBlueprint;
import io.github.dabaihenhaochi.wayside.building.BuildingInstance;
import io.github.dabaihenhaochi.wayside.building.ConstructionPlanner;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Rotation;
import java.util.UUID;
import io.github.dabaihenhaochi.wayside.entity.task.NpcTaskRunner;
import io.github.dabaihenhaochi.wayside.entity.task.NpcTaskExecutionGoal;

public class NpcEntity extends PathfinderMob {
    private final NpcTaskRunner taskRunner = new NpcTaskRunner(this);
    public NpcTaskRunner getTaskRunner() { return taskRunner; }

    private BlockPos moveTarget;
    private BlockPos blockTarget;

    private static final long FAILED_TARGET_COOLDOWN_TICKS = 1200L;
    private final Map<BlockPos, Long> failedBlockTargets = new HashMap<>();

    private final SimpleContainer inventory = new SimpleContainer(9);
    private static final int TARGET_LOG_COUNT = 16;

    public enum TaskStage {
        COLLECTING,
        CRAFTING,
        COMPLETED
    }

    private TaskStage taskStage = TaskStage.COLLECTING;

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
        this.goalSelector.addGoal(0, new NpcTaskExecutionGoal(this));
        this.goalSelector.addGoal(4, new LookAtPlayerGoal(this, Player.class, 8.0F));
        this.goalSelector.addGoal(5, new RandomLookAroundGoal(this));
    }

    @Override
    protected InteractionResult mobInteract(Player player, InteractionHand hand) {
        if (!this.level().isClientSide) {
            this.taskRunner.temporarilyMoveTo(player.blockPosition());

            player.sendSystemMessage(Component.literal(
                    "任务阶段：" + this.taskRunner.description()
                            + "，原木：" + this.getLogCount()
                            + "，木板：" + this.getPlankCount()
            ));

            if (this.level() instanceof ServerLevel serverLevel) {

                ResourceLocation blueprintId =
                        ResourceLocation.fromNamespaceAndPath(
                                "wayside", "test_house"
                        );

                BuildingBlueprint.load(serverLevel, blueprintId)
                        .ifPresentOrElse(blueprint -> {

                            BuildingInstance testBuilding = new BuildingInstance(
                                    UUID.randomUUID(),
                                    blueprintId,
                                    player.blockPosition().offset(3, 0, 3),
                                    Rotation.NONE
                            );

                            var plan = ConstructionPlanner.createPlan(
                                    serverLevel,
                                    blueprint,
                                    testBuilding,
                                    ConstructionPlanner.AirPolicy.IGNORE
                            );

                            player.sendSystemMessage(Component.literal(
                                    "待施工：" + plan.tasks().size()
                                            + "，不可读取：" + plan.unavailablePositions()
                                            + "，未核验方块实体：" + plan.uncheckedBlockEntities()
                            ));

                            plan.tasks().stream().limit(5).forEach(task ->
                                    player.sendSystemMessage(Component.literal(
                                            task.action()
                                                    + " " + task.pos()
                                                    + " -> " + task.targetState()
                                    ))
                            );

                        }, () -> player.sendSystemMessage(
                                Component.literal("找不到建筑蓝图")
                        ));
            }

        }

        return InteractionResult.sidedSuccess(this.level().isClientSide);
    }


    // Compatibility entry points for older tests and existing external callers.
    public void setMoveTarget(BlockPos target) {
        taskRunner.temporarilyMoveTo(target);
    }

    public BlockPos getMoveTarget() { return taskRunner.getMoveTarget(); }
    public boolean hasMoveTarget() { return getMoveTarget() != null; }
    public void clearMoveTarget() { taskRunner.cancelManualMove(); }
    public BlockPos getBlockTarget() { return taskRunner.getBlockTarget(); }
    /** Legacy API retained while the old Goal classes are migrated. */
    @Deprecated
    public void setBlockTarget(BlockPos target) { taskRunner.abandonWorkTarget(); }
    public boolean hasBlockTarget() { return getBlockTarget() != null; }
    public void clearBlockTarget() { taskRunner.abandonWorkTarget(); }
    public boolean canReachBlock(BlockPos target) {
        return NpcBlockInteraction.canWorkAt(this, target, this.position());
    }
    public boolean planMoveToBlockTarget() { return false; }
    public void markBlockTargetFailed(BlockPos target) { taskRunner.markBlockFailed(target); }
    public boolean isBlockTargetOnCooldown(BlockPos target) {
        return taskRunner.isBlockOnCooldown(target);
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
        // TaskRunner derives work progress from the inventory on its next tick.
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
        return taskRunner.description().contains("COLLECTING") && this.getLogCount() < TARGET_LOG_COUNT;
    }

    public boolean wantsItem(ItemStack stack) {
        return taskRunner.wantsItem(stack);
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);

        tag.putString("WaysideTaskStage", this.getTaskStage().name());
        tag.put("WaysideTask", taskRunner.save());

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

        this.taskStage = TaskStage.COLLECTING;

        if (tag.contains("WaysideTaskStage", Tag.TAG_STRING)) {
            try {
                this.taskStage = TaskStage.valueOf(
                        tag.getString("WaysideTaskStage")
                );
            } catch (IllegalArgumentException e) {
                this.taskStage = TaskStage.COLLECTING;
            }
        }

        this.inventory.clearContent();

        if (tag.contains("WaysideInventory", Tag.TAG_COMPOUND)) {
            ContainerHelper.loadAllItems(
                    tag.getCompound("WaysideInventory"),
                    this.inventory.getItems(),
                    this.level().registryAccess()
            );
        }
        taskRunner.load(tag);
    }

    public TaskStage getTaskStage() {
        if (taskRunner.description().contains("CRAFTING")) return TaskStage.CRAFTING;
        if (taskRunner.description().contains("COMPLETED")) return TaskStage.COMPLETED;
        return TaskStage.COLLECTING;
    }

    private void updateTaskStage() {
        if (this.taskStage == TaskStage.COLLECTING
                && this.getLogCount() >= TARGET_LOG_COUNT) {

            this.taskStage = TaskStage.CRAFTING;
            this.clearBlockTarget();
        }
    }

    public void finishCrafting() {
        if (this.taskStage == TaskStage.CRAFTING) {
            this.taskStage = TaskStage.COMPLETED;
        }
    }

    public int getPlankCount() {
        int count = 0;

        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);

            if (stack.is(ItemTags.PLANKS)) {
                count += stack.getCount();
            }
        }

        return count;
    }
}
