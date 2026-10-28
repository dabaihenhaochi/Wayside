package io.github.dabaihenhaochi.wayside.gametest;

import io.github.dabaihenhaochi.wayside.Wayside;
import io.github.dabaihenhaochi.wayside.entity.NpcEntity;
import io.github.dabaihenhaochi.wayside.entity.task.build.BuildHutTask;
import io.github.dabaihenhaochi.wayside.registry.ModEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(Wayside.MODID)
@PrefixGameTestTemplate(false)
public final class NpcInitialSuppliesGameTests {
    private static void assertInitialStock(GameTestHelper helper, NpcEntity npc) {
        helper.assertTrue(npc.getInventory().countItem(Items.OAK_LOG) == 16
                        && npc.getInventory().countItem(Items.OAK_PLANKS) == 64
                        && npc.getInventory().countItem(Items.OAK_STAIRS) == 64
                        && npc.getInventory().countItem(Items.DIRT) == 64,
                "A fresh NPC must immediately have the configured initial inventory");
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void hasInventoryBeforeBeingAddedOrRunningAnyTask(GameTestHelper helper) {
        NpcEntity npc = ModEntities.NPC.get().create(helper.getLevel());
        npc.getTaskRunner().cancel();
        npc.setNoAi(true);
        assertInitialStock(helper, npc);
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void summonNbtWithoutInventoryPreservesStartingStock(GameTestHelper helper) {
        CompoundTag summon = new CompoundTag();
        summon.putString("id", "wayside:npc");
        summon.putBoolean("NoAI", true);
        var loaded = EntityType.create(summon, helper.getLevel()).orElseThrow();
        helper.assertTrue(loaded instanceof NpcEntity, "The summon path must create an NPC");
        NpcEntity npc = (NpcEntity) loaded;
        assertInitialStock(helper, npc);
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void spawnEggCreationAlsoStartsWithInventory(GameTestHelper helper) {
        NpcEntity npc = ModEntities.NPC.get().spawn(helper.getLevel(),
                helper.absolutePos(new BlockPos(2, 1, 2)), MobSpawnType.SPAWN_EGG);
        helper.assertTrue(npc != null, "Spawn-egg creation should succeed");
        npc.setNoAi(true);
        npc.getTaskRunner().cancel();
        assertInitialStock(helper, npc);
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void savedInventoryReplacesDefaultsWithoutMergingOrRefilling(GameTestHelper helper) {
        NpcEntity original = ModEntities.NPC.get().create(helper.getLevel());
        original.getTaskRunner().cancel();
        original.getInventory().clearContent();
        original.getInventory().setItem(0, new ItemStack(Items.DIAMOND, 3));
        original.getInventory().setItem(2, new ItemStack(Items.DIRT, 7));
        CompoundTag saved = new CompoundTag();
        original.addAdditionalSaveData(saved);
        // 旧版的发放标记不应覆盖已保存的库存，也不能重新授权补发。
        saved.putBoolean("WaysideInitialSuppliesGiven", false);
        NpcEntity restored = ModEntities.NPC.get().create(helper.getLevel());
        restored.readAdditionalSaveData(saved);
        restored.readAdditionalSaveData(saved);
        helper.assertTrue(restored.getInventory().countItem(Items.DIAMOND) == 3
                        && restored.getInventory().countItem(Items.DIRT) == 7
                        && restored.getLogCount() == 0 && restored.getPlankCount() == 0
                        && restored.getInventory().countItem(Items.OAK_STAIRS) == 0,
                "Repeated loading must exactly restore saved quantities and remove all constructor defaults");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void emptyLegacyInventoryStaysEmptyWhenBuildTaskIsRestarted(GameTestHelper helper) {
        NpcEntity original = ModEntities.NPC.get().create(helper.getLevel());
        original.getInventory().clearContent();
        CompoundTag saved = new CompoundTag();
        original.addAdditionalSaveData(saved);
        saved.remove("WaysideInitialSuppliesGiven");
        NpcEntity restored = ModEntities.NPC.get().create(helper.getLevel());
        restored.readAdditionalSaveData(saved);
        restored.setPos(helper.absolutePos(new BlockPos(2, 1, 2)).getCenter());
        restored.setOnGround(true);
        for (int i = 0; i < 3; i++) new BuildHutTask().tick(restored);
        helper.assertTrue(restored.getInventory().isEmpty(),
                "An empty saved inventory must stay empty, even when a new building task is started repeatedly");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void differentNpcsOwnIndependentItemStacks(GameTestHelper helper) {
        NpcEntity first = ModEntities.NPC.get().create(helper.getLevel());
        NpcEntity second = ModEntities.NPC.get().create(helper.getLevel());
        first.getInventory().getItem(0).shrink(5);
        first.getInventory().removeItem(1, 10);
        helper.assertTrue(first.getLogCount() == 11 && first.getPlankCount() == 54,
                "Fixture should consume material from the first NPC");
        assertInitialStock(helper, second);
        helper.succeed();
    }
}
