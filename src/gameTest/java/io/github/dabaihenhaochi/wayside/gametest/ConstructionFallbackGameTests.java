package io.github.dabaihenhaochi.wayside.gametest;

import io.github.dabaihenhaochi.wayside.Wayside;
import io.github.dabaihenhaochi.wayside.building.BuildingInstance;
import io.github.dabaihenhaochi.wayside.entity.NpcEntity;
import io.github.dabaihenhaochi.wayside.entity.action.ActionResult;
import io.github.dabaihenhaochi.wayside.entity.action.PlaceBlockAction;
import io.github.dabaihenhaochi.wayside.entity.ai.NpcBlockInteraction;
import io.github.dabaihenhaochi.wayside.entity.task.NpcTaskRunner;
import io.github.dabaihenhaochi.wayside.entity.task.build.BuildStructureTask;
import io.github.dabaihenhaochi.wayside.registry.ModEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

@GameTestHolder(Wayside.MODID)
@PrefixGameTestTemplate(false)
public final class ConstructionFallbackGameTests {
    private static final BlockPos PROTOTYPE = new BlockPos(1, 8, 1);
    private static final BlockPos ORIGIN = new BlockPos(9, 1, 5);

    private static NpcEntity prepare(GameTestHelper helper) {
        for (int x = 0; x < 15; x++) for (int z = 0; z < 13; z++) helper.setBlock(x, 0, z, Blocks.STONE);
        NpcEntity npc = helper.spawn(ModEntities.NPC.get(), new BlockPos(2, 1, 5));
        npc.getTaskRunner().cancel();
        npc.getInventory().clearContent();
        return npc;
    }

    private static BuildingInstance capture(GameTestHelper helper, String name, Vec3i size) {
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath("wayside", "fallback_" + name);
        helper.getLevel().getStructureManager().getOrCreate(id).fillFromWorld(helper.getLevel(),
                helper.absolutePos(PROTOTYPE), size, false, Blocks.STRUCTURE_VOID);
        for (int x = 0; x < size.getX(); x++) for (int y = 0; y < size.getY(); y++)
            for (int z = 0; z < size.getZ(); z++) helper.setBlock(PROTOTYPE.offset(x, y, z), Blocks.AIR);
        return new BuildingInstance(UUID.randomUUID(), id, helper.absolutePos(ORIGIN), Rotation.NONE);
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void remotePlacementUsesExactCostAndIsIdempotent(GameTestHelper helper) {
        NpcEntity npc = prepare(helper);
        BlockPos target = helper.absolutePos(ORIGIN.above(5));
        helper.setBlock(ORIGIN.above(5), Blocks.AIR); // 清除测试框架在模板上方生成的屏障。
        var state = Blocks.OAK_SLAB.defaultBlockState().setValue(BlockStateProperties.SLAB_TYPE, SlabType.DOUBLE);
        npc.getInventory().setItem(0, new ItemStack(Items.OAK_SLAB, 2));
        var ordinary = new PlaceBlockAction(target, state).tick(npc);
        helper.assertTrue(ordinary.failure() == ActionResult.Failure.UNREACHABLE,
                "Ordinary placement must retain reach/sight checks: " + ordinary + "; target=" + helper.getLevel().getBlockState(target));
        var fallback = PlaceBlockAction.constructionFallback(target, state);
        helper.assertTrue(fallback.tick(npc).isSuccess(), "Construction fallback must reach a distant high target");
        helper.assertTrue(npc.getInventory().countItem(Items.OAK_SLAB) == 0, "Double slabs must still cost two items");
        helper.assertTrue(fallback.tick(npc).isSuccess(), "Rechecking a completed fallback must not charge again");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void canceledRemotePlacementRollsBackAndRetainsMaterials(GameTestHelper helper) {
        NpcEntity npc = prepare(helper);
        BlockPos target = helper.absolutePos(ORIGIN.above(5));
        helper.setBlock(ORIGIN.above(5), Blocks.AIR);
        npc.getInventory().setItem(0, new ItemStack(Items.GLASS, 1));
        Consumer<BlockEvent.EntityPlaceEvent> listener = event -> {
            if (event.getEntity() == npc && event.getPos().equals(target)) event.setCanceled(true);
        };
        NeoForge.EVENT_BUS.addListener(listener);
        try {
            var result = PlaceBlockAction.constructionFallback(target, Blocks.GLASS.defaultBlockState()).tick(npc);
            helper.assertTrue(result.failure() == ActionResult.Failure.FORBIDDEN,
                    "Remote placement must respect events: " + result + "; target=" + helper.getLevel().getBlockState(target));
            helper.assertTrue(helper.getLevel().getBlockState(target).isAir(), "Canceled remote placement must roll back");
            helper.assertTrue(npc.getInventory().countItem(Items.GLASS) == 1, "Cancellation must not consume materials");
        } finally { NeoForge.EVENT_BUS.unregister(listener); }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void remotePlacementStillRequiresMaterialsSupportAndBodySpace(GameTestHelper helper) {
        NpcEntity npc = prepare(helper);
        BlockPos target = helper.absolutePos(ORIGIN.above(5));
        helper.setBlock(ORIGIN.above(5), Blocks.AIR);
        helper.setBlock(ORIGIN.above(4), Blocks.AIR);
        var missing = PlaceBlockAction.constructionFallback(target, Blocks.GLASS.defaultBlockState()).tick(npc);
        helper.assertTrue(missing.failure() == ActionResult.Failure.MISSING_INGREDIENT,
                "A remote target must require materials: " + missing + "; target=" + helper.getLevel().getBlockState(target));
        npc.getInventory().setItem(0, new ItemStack(Items.SAND, 1));
        helper.assertTrue(PlaceBlockAction.constructionFallback(target, Blocks.SAND.defaultBlockState()).tick(npc)
                        .failure() == ActionResult.Failure.NO_SPACE,
                "Remote placement cannot suspend falling blocks without support");
        helper.assertTrue(npc.getInventory().countItem(Items.SAND) == 1, "Unsupported placement must retain sand");
        npc.getInventory().setItem(0, new ItemStack(Items.GLASS, 1));
        helper.assertTrue(PlaceBlockAction.constructionFallback(npc.blockPosition(), Blocks.GLASS.defaultBlockState())
                        .tick(npc).failure() == ActionResult.Failure.NO_SPACE,
                "Remote placement must not embed the builder in a block");
        helper.assertTrue(npc.getInventory().countItem(Items.GLASS) == 1, "Body collision must retain glass");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 250)
    public static void constructionWalksToVisibleStationBeforePlacing(GameTestHelper helper) {
        NpcEntity npc = prepare(helper);
        helper.setBlock(PROTOTYPE, Blocks.OAK_PLANKS);
        var building = capture(helper, "visible", new Vec3i(1, 1, 1));
        npc.getInventory().setItem(0, new ItemStack(Items.OAK_PLANKS, 1));
        double startX = npc.getX();
        npc.getTaskRunner().setTask(new BuildStructureTask(building));
        helper.succeedWhen(() -> {
            helper.assertTrue(npc.getTaskRunner().state() == NpcTaskRunner.State.COMPLETED,
                    "Visible construction should complete");
            helper.assertTrue(npc.getX() > startX + 1, "Builder must first approach a reachable normal station");
            helper.assertTrue(NpcBlockInteraction.canReachBlockFrom(npc, building.origin(), npc.position()),
                    "Reachable construction must finish within normal reach and sight");
        });
    }

    @GameTest(template = "empty", timeoutTicks = 180)
    public static void stalledNormalMovementFallsBackAfterShortAttempt(GameTestHelper helper) {
        NpcEntity npc = prepare(helper);
        npc.setNoAi(true); // 手动推进任务，阻止导航实际移动，确定性覆盖移动超时。
        npc.setOnGround(true);
        helper.setBlock(PROTOTYPE, Blocks.OAK_PLANKS);
        var task = new BuildStructureTask(capture(helper, "timeout", new Vec3i(1, 1, 1)));
        npc.getInventory().setItem(0, new ItemStack(Items.OAK_PLANKS, 1));
        long started = helper.getLevel().getGameTime();
        boolean[] moving = {false};
        helper.onEachTick(() -> {
            npc.setOnGround(true);
            var result = task.tick(npc);
            moving[0] |= task.stageName().equals("MOVING");
            helper.assertTrue(!result.isFailed(), "A failed approach must not fail the building task");
            if (result.isSuccess()) {
                helper.assertTrue(moving[0] && helper.getLevel().getGameTime() - started >= 60,
                        "Must try normal movement before timing out to fallback");
                helper.assertBlockPresent(Blocks.OAK_PLANKS, ORIGIN);
                helper.assertTrue(npc.getInventory().countItem(Items.OAK_PLANKS) == 0,
                        "Timed-out movement must still charge only the placed block");
                task.pause(npc);
                helper.succeed();
            }
        });
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void buildsBottomUpThenAttachmentsBehindObstructions(GameTestHelper helper) {
        NpcEntity npc = prepare(helper);
        // 把工人围住，保证没有普通视线站位，建筑必须通过兜底完成。
        for (int x = 1; x <= 3; x++) for (int z = 4; z <= 6; z++) for (int y = 1; y <= 3; y++) {
            if (x != 2 || z != 5 || y == 3) helper.setBlock(x, y, z, Blocks.STONE);
        }
        for (int x = 0; x < 2; x++) for (int y = 0; y < 3; y++)
            helper.setBlock(PROTOTYPE.offset(x, y, 0), Blocks.STRUCTURE_VOID);
        helper.setBlock(PROTOTYPE, Blocks.OAK_PLANKS);
        helper.setBlock(PROTOTYPE.above(), Blocks.TORCH);
        helper.setBlock(PROTOTYPE.offset(1, 0, 0), Blocks.OAK_PLANKS);
        helper.setBlock(PROTOTYPE.offset(1, 1, 0), Blocks.OAK_PLANKS);
        helper.setBlock(PROTOTYPE.offset(1, 2, 0), Blocks.OAK_PLANKS);
        var building = capture(helper, "order", new Vec3i(2, 3, 1));
        npc.getInventory().setItem(0, new ItemStack(Items.OAK_PLANKS, 4));
        npc.getInventory().setItem(1, new ItemStack(Items.TORCH, 1));
        List<BlockPos> placed = new ArrayList<>();
        Consumer<BlockEvent.EntityPlaceEvent> listener = event -> {
            if (event.getEntity() == npc) placed.add(event.getPos().immutable());
        };
        npc.setNoAi(true);
        var task = new BuildStructureTask(building);
        boolean[] completed = {false};
        helper.onEachTick(() -> {
            if (completed[0]) return;
            npc.setOnGround(true);
            NeoForge.EVENT_BUS.addListener(listener);
            try {
                var result = task.tick(npc);
                helper.assertTrue(!result.isFailed(), "Occluded construction must not fail");
                completed[0] = result.isSuccess();
            } finally { NeoForge.EVENT_BUS.unregister(listener); }
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(completed[0],
                    "Occluded construction should finish through fallback");
            helper.assertTrue(placed.size() == 5, "Every blueprint block must be placed exactly once");
            int previousY = Integer.MIN_VALUE;
            for (int i = 0; i < 4; i++) {
                helper.assertTrue(placed.get(i).getY() >= previousY, "Main blocks must be built from low to high");
                previousY = placed.get(i).getY();
            }
            helper.assertTrue(placed.get(4).equals(building.origin().above()),
                    "The low torch must be deferred until the higher main structure is complete");
            helper.assertTrue(npc.getInventory().countItem(Items.OAK_PLANKS) == 0
                            && npc.getInventory().countItem(Items.TORCH) == 0,
                    "Occluded building must consume exactly the blueprint cost");
        });
    }
}
