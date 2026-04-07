package me.sisui.fpv_drone;

import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;

/**
 * ドローンモッドのエンティティ登録を管理するクラス。
 */
public class EntityRegistry {

    // ドローンエンティティのリソースキー
    public static final ResourceKey<EntityType<?>> DRONE_KEY = ResourceKey.create(
            Registries.ENTITY_TYPE,
            Identifier.fromNamespaceAndPath(FPVDroneMod.MOD_ID, "drone")
    );

    // ドローンエンティティタイプの登録
    // クライアント側での追跡範囲（512ブロック）と更新間隔（1ティック）を指定します
    public static final EntityType<DroneEntity> DRONE = Registry.register(
            BuiltInRegistries.ENTITY_TYPE,
            DRONE_KEY,
            EntityType.Builder.<DroneEntity>of(DroneEntity::new, MobCategory.MISC)
                    .sized(1.0f, 0.25f)
                    .clientTrackingRange(512)
                    .updateInterval(1)
                    .build(DRONE_KEY)
    );

    /**
     * エンティティの属性（HPや移動速度など）を登録します。
     * ModInitialize時に呼び出されます。
     */
    public static void registerEntities() {
        FabricDefaultAttributeRegistry.register(DRONE, DroneEntity.createDroneAttributes());
    }
}
