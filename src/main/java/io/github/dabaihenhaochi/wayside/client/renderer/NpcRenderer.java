package io.github.dabaihenhaochi.wayside.client.renderer;

import io.github.dabaihenhaochi.wayside.entity.NpcEntity;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.resources.ResourceLocation;

public class NpcRenderer
        extends HumanoidMobRenderer<NpcEntity, HumanoidModel<NpcEntity>> {

    private static final ResourceLocation TEXTURE =
            ResourceLocation.fromNamespaceAndPath(
                    "minecraft",
                    "textures/entity/zombie/zombie.png"
            );

    public NpcRenderer(EntityRendererProvider.Context context) {
        super(
                context,
                new HumanoidModel<>(context.bakeLayer(ModelLayers.ZOMBIE)),
                0.5F
        );
    }

    @Override
    public ResourceLocation getTextureLocation(NpcEntity entity) {
        return TEXTURE;
    }
}
