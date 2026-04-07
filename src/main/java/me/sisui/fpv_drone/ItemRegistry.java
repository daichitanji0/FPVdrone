package me.sisui.fpv_drone;

import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;

/**
 * ドローンモッドのアイテム登録を管理するクラス。
 */
public class ItemRegistry {
    // ドローンアイテムとコントローラーアイテムのリソースキー
    public static final ResourceKey<Item> DRONE_KEY = ResourceKey.create(
            Registries.ITEM,
            Identifier.fromNamespaceAndPath(FPVDroneMod.MOD_ID, "drone")
    );
    public static final ResourceKey<Item> CONTROLLER_KEY = ResourceKey.create(
            Registries.ITEM,
            Identifier.fromNamespaceAndPath(FPVDroneMod.MOD_ID, "controller")
    );

    // アイテムインスタンスの生成（スタックサイズを1に設定）
    public static final Item DRONE = new DroneItem(new Item.Properties().setId(DRONE_KEY).stacksTo(1));
    public static final Item CONTROLLER = new ControllerItem(new Item.Properties().setId(CONTROLLER_KEY).stacksTo(1));

    /**
     * カスタムアイテムをゲームに登録し、クリエイティブタブに追加します。
     * ModInitialize時に呼び出されます。
     */
    public static void registerItems() {

        // ドローンアイテムの登録
        Registry.register(
                BuiltInRegistries.ITEM,
                DRONE_KEY,
                DRONE
        );

        // コントローラーアイテムの登録
        Registry.register(
                BuiltInRegistries.ITEM,
                CONTROLLER_KEY,
                CONTROLLER
        );

        // 「ツールとユーティリティ」のクリエイティブタブにアイテムを追加
        ItemGroupEvents.modifyEntriesEvent(CreativeModeTabs.TOOLS_AND_UTILITIES).register(entries -> {
            entries.accept(DRONE);
            entries.accept(CONTROLLER);
        });
    }
}