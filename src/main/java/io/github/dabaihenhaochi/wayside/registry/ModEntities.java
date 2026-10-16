package io.github.dabaihenhaochi.wayside.registry;

import io.github.dabaihenhaochi.wayside.Wayside;
import io.github.dabaihenhaochi.wayside.entity.NpcEntity;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.function.Supplier;

public class ModEntities {

    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
            DeferredRegister.create(BuiltInRegistries.ENTITY_TYPE, Wayside.MODID);

    public static final Supplier<EntityType<NpcEntity>> NPC =
            ENTITY_TYPES.register(
                    "npc",
                    () -> EntityType.Builder.of(
                                    NpcEntity::new,
                                    MobCategory.CREATURE
                            )
                            .sized(0.6F, 1.8F)
                            .build(Wayside.MODID + ":npc")
            );

    public static void registerAttributes(EntityAttributeCreationEvent event) {
        event.put(
                NPC.get(),
                NpcEntity.createAttributes().build()
        );
    }

    public static void register(IEventBus eventBus) {
        ENTITY_TYPES.register(eventBus);
        eventBus.addListener(ModEntities::registerAttributes);
    }
}
