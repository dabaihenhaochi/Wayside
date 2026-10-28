package io.github.dabaihenhaochi.wayside.entity;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.level.Level;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.tags.ItemTags;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.ContainerHelper;
import io.github.dabaihenhaochi.wayside.entity.inventory.NpcInitialSupplies;
import io.github.dabaihenhaochi.wayside.entity.task.NpcTaskRunner;
import io.github.dabaihenhaochi.wayside.entity.task.NpcTaskExecutionGoal;
import io.github.dabaihenhaochi.wayside.entity.task.build.BuildHutTask;

// 连接任务运行器与背包；初始物资属于实体创建，读档时以保存的背包为准。
public class NpcEntity extends PathfinderMob {
    //创建任务运行器
    private final NpcTaskRunner taskRunner = new NpcTaskRunner(this, BuildHutTask::new);
    public NpcTaskRunner getTaskRunner() { return taskRunner; }

    //创建背包
    private static final int INVENTORY_SIZE = 9;
    private final SimpleContainer inventory;
    public NpcEntity(EntityType<? extends PathfinderMob> entityType, Level level) {
        super(entityType, level);
        inventory = level.isClientSide ? new SimpleContainer(INVENTORY_SIZE)
                : NpcInitialSupplies.createInventory(INVENTORY_SIZE);
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

    public SimpleContainer getInventory() {
        return this.inventory;
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

    // 保存任务状态和背包内容。
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

    // 先恢复背包，再加载任务，供后续按实际库存规划。
    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);

        // /summon 的初始 NBT 可能没有背包字段，此时保留构造时的默认物资。
        // 存档有背包字段时完整替换，包括空背包，绝不按缺口补发。
        if (tag.contains("WaysideInventory", Tag.TAG_COMPOUND)) {
            this.inventory.clearContent();
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
