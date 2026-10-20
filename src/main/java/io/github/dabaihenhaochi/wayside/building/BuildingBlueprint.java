
package io.github.dabaihenhaochi.wayside.building;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class BuildingBlueprint {

    private final ResourceLocation id;
    private final StructureTemplate template;
    private final List<StructureTemplate.StructureBlockInfo> blocks;

    private BuildingBlueprint(
            ResourceLocation id,
            StructureTemplate template,
            List<StructureTemplate.StructureBlockInfo> blocks
    ) {
        this.id = id;
        this.template = template;
        this.blocks = List.copyOf(blocks);
    }

    public ResourceLocation getId() {
        return id;
    }

    public Vec3i getSize() {
        return template.getSize();
    }

    public StructureTemplate getTemplate() {
        return template;
    }

    public List<StructureTemplate.StructureBlockInfo> getBlocks() {
        return blocks;
    }

    public static Optional<BuildingBlueprint> load(
            ServerLevel level,
            ResourceLocation id
    ) {
        return level.getStructureManager()
                .get(id)
                .map(template -> new BuildingBlueprint(
                        id,
                        template,
                        readBlocks(template)
                ));
    }

    private static List<StructureTemplate.StructureBlockInfo> readBlocks(
            StructureTemplate template
    ) {
        CompoundTag data = template.save(new CompoundTag());

        ListTag palette = data.getList("palette", Tag.TAG_COMPOUND);

        if (palette.isEmpty()) {
            ListTag palettes = data.getList("palettes", Tag.TAG_LIST);
            if (!palettes.isEmpty()) {
                palette = palettes.getList(0);
            }
        }

        ListTag blockData = data.getList("blocks", Tag.TAG_COMPOUND);
        List<StructureTemplate.StructureBlockInfo> result = new ArrayList<>();

        for (int i = 0; i < blockData.size(); i++) {
            CompoundTag entry = blockData.getCompound(i);

            ListTag position = entry.getList("pos", Tag.TAG_INT);
            if (position.size() != 3) {
                throw new IllegalArgumentException("Invalid blueprint position");
            }

            BlockPos pos = new BlockPos(
                    position.getInt(0),
                    position.getInt(1),
                    position.getInt(2)
            );

            int stateIndex = entry.getInt("state");
            if (stateIndex < 0 || stateIndex >= palette.size()) {
                throw new IllegalArgumentException("Invalid blueprint state");
            }

            BlockState state = NbtUtils.readBlockState(
                    BuiltInRegistries.BLOCK.asLookup(),
                    palette.getCompound(stateIndex)
            );

            CompoundTag blockEntityData =
                    entry.contains("nbt", Tag.TAG_COMPOUND)
                            ? entry.getCompound("nbt").copy()
                            : null;

            result.add(new StructureTemplate.StructureBlockInfo(
                    pos,
                    state,
                    blockEntityData
            ));
        }

        return result;
    }
}
