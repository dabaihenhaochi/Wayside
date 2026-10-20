package io.github.dabaihenhaochi.wayside.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import io.github.dabaihenhaochi.wayside.entity.ai.NpcBlockInteraction;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.tags.ItemTags;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.ContainerHelper;
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
    //创建任务运行器
    private final NpcTaskRunner taskRunner = new NpcTaskRunner(this);
    public NpcTaskRunner getTaskRunner() { return taskRunner; }

    //创建背包
    private final SimpleContainer inventory = new SimpleContainer(9);
    //构造方法
    public NpcEntity(EntityType<? extends PathfinderMob> entityType, Level level) {
        super(entityType, level);
    }

    //npc属性：最大生命值，移动速度，目标追踪范围
    public static AttributeSupplier.Builder createAttributes() {
        return PathfinderMob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 20.0)
                .add(Attributes.MOVEMENT_SPEED, 0.25)
                .add(Attributes.FOLLOW_RANGE, 32.0);
    }

    //注册目标，设置优先级
    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(0, new NpcTaskExecutionGoal(this));
        this.goalSelector.addGoal(4, new LookAtPlayerGoal(this, Player.class, 8.0F));
        this.goalSelector.addGoal(5, new RandomLookAroundGoal(this));
    }

    //玩家交互
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

    public SimpleContainer getInventory() {
        return this.inventory;
    }

    //每tick物品拾取
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

    //检索背包原木数量
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

    public boolean wantsItem(ItemStack stack) {
        return taskRunner.wantsItem(stack);
    }

    //保存背包内容
    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);

        tag.put("WaysideTask", taskRunner.save());

        CompoundTag inventoryTag = new CompoundTag();

        ContainerHelper.saveAllItems(
                inventoryTag,
                this.inventory.getItems(),
                this.level().registryAccess()
        );

        tag.put("WaysideInventory", inventoryTag);
    }

    //加载背包内容
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
        taskRunner.load(tag);
    }

    //检索背包木板数量
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
