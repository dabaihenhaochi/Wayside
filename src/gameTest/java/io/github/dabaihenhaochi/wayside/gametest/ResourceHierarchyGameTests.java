package io.github.dabaihenhaochi.wayside.gametest;

import io.github.dabaihenhaochi.wayside.Wayside;
import io.github.dabaihenhaochi.wayside.entity.NpcEntity;
import io.github.dabaihenhaochi.wayside.entity.task.NpcTaskRunner;
import io.github.dabaihenhaochi.wayside.entity.task.resource.EnsurePlanksTask;
import io.github.dabaihenhaochi.wayside.entity.task.resource.SupplyMaterialsTask;
import io.github.dabaihenhaochi.wayside.registry.ModEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.Map;

@GameTestHolder(Wayside.MODID)
@PrefixGameTestTemplate(false)
public final class ResourceHierarchyGameTests {
    private static NpcEntity npc(GameTestHelper helper) {
        for (int x = 0; x < 12; x++) for (int z = 0; z < 12; z++) helper.setBlock(x, 0, z, Blocks.STONE);
        NpcEntity npc = helper.spawn(ModEntities.NPC.get(), new BlockPos(4, 1, 5));
        npc.getTaskRunner().cancel();
        npc.getInventory().clearContent();
        return npc;
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void partiallyCraftedRecipeReplansWithoutRepeatingFinishedCrafts(GameTestHelper helper) {
        NpcEntity npc = npc(helper);
        helper.setBlock(3, 1, 5, Blocks.CRAFTING_TABLE);
        npc.getInventory().setItem(0, new ItemStack(Items.OAK_LOG, 5));
        npc.getTaskRunner().setTask(new SupplyMaterialsTask(Map.of(Items.OAK_STAIRS, 12)));
        boolean[] reloaded = {false};
        helper.onEachTick(() -> {
            helper.assertTrue(npc.getTaskRunner().state() != NpcTaskRunner.State.FAILED, "Material production must remain executable");
            if (!reloaded[0] && npc.getInventory().countItem(Items.OAK_STAIRS) == 4) {
                npc.getTaskRunner().pause();
                CompoundTag saved = new CompoundTag();
                saved.put("WaysideTask", npc.getTaskRunner().save());
                npc.getTaskRunner().load(saved);
                helper.assertTrue(npc.getTaskRunner().state() == NpcTaskRunner.State.PAUSED, "Reload must preserve pause");
                npc.getTaskRunner().resume();
                reloaded[0] = true;
            }
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(reloaded[0] && npc.getTaskRunner().state() == NpcTaskRunner.State.COMPLETED,
                    "Partial recipe progress must resume through a new nested production chain");
            helper.assertTrue(npc.getInventory().countItem(Items.OAK_STAIRS) == 12
                            && npc.getInventory().countItem(Items.OAK_PLANKS) == 2 && npc.getInventory().countItem(Items.OAK_LOG) == 0,
                    "Five logs must pay for twelve stairs exactly once, retaining two surplus planks");
        });
    }

    @GameTest(template = "empty", timeoutTicks = 220)
    public static void plankGoalReloadStopsAtItsOwnTargetAndRetainsSpareLogs(GameTestHelper helper) {
        NpcEntity npc = npc(helper);
        npc.getInventory().setItem(0, new ItemStack(Items.OAK_LOG, 3));
        npc.getInventory().setItem(1, new ItemStack(Items.OAK_PLANKS, 1));
        npc.getTaskRunner().setTask(new EnsurePlanksTask(9));
        boolean[] reloaded = {false};
        helper.onEachTick(() -> {
            helper.assertTrue(npc.getTaskRunner().state() != NpcTaskRunner.State.FAILED, "Plank goal must remain executable");
            if (!reloaded[0] && npc.getInventory().countItem(Items.OAK_PLANKS) == 5) {
                CompoundTag saved = new CompoundTag();
                saved.put("WaysideTask", npc.getTaskRunner().save());
                npc.getTaskRunner().load(saved);
                reloaded[0] = true;
            }
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(reloaded[0] && npc.getTaskRunner().state() == NpcTaskRunner.State.COMPLETED,
                    "The plank target must survive a reload between crafts");
            helper.assertTrue(npc.getInventory().countItem(Items.OAK_PLANKS) == 9 && npc.getInventory().countItem(Items.OAK_LOG) == 1,
                    "Rechecking a target must retain logs that are no longer needed");
        });
    }
}
