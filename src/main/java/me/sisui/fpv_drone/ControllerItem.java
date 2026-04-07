package me.sisui.fpv_drone;

import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * ドローンを操縦するためのコントローラーアイテムクラス。
 * 所持しているコントローラーに紐づいた周波数のドローンを探し、操縦モードに入ります。
 */
public class ControllerItem extends Item {
    public ControllerItem(Properties properties) {
        super(properties);
    }

    @Override
    public net.minecraft.world.InteractionResult use(Level level, Player player, InteractionHand hand) {
        ItemStack itemStack = player.getItemInHand(hand);
        
        // コントローラーに記録されているドローンの周波数を読み込む
        CustomData cd = itemStack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY);
        int freq = cd.copyTag().getInt("DroneFrequency").orElse(0);

        if (freq > 0) {
            // パラメータとして設定された周波数を持つドローンを、プレイヤーの周辺（256ブロック内）から検索
            java.util.List<DroneEntity> drones = level.getEntitiesOfClass(DroneEntity.class, player.getBoundingBox().inflate(256.0), d -> d.getFrequency() == freq);
            if (drones.isEmpty()) {
                if (level.isClientSide()) {
                    player.displayClientMessage(Component.literal("§c操縦できるドローンがいません"), true);
                }
                return net.minecraft.world.InteractionResult.FAIL;
            }

            // クライアント側で操縦モードの初期化を行う
            if (level.isClientSide()) {
                if (OperatingState.isOperatingDrone) {
                    player.displayClientMessage(Component.literal("§cすでにドローンを操作しています"), true);
                    return net.minecraft.world.InteractionResult.FAIL;
                }
                
                // 操作状態変数のリセットと初期化
                OperatingState.isOperatingDrone = true;
                OperatingState.operatingFrequency = freq;
                OperatingState.roll = 0;
                OperatingState.droneYaw = player.getYRot();
                OperatingState.targetPitch = player.getXRot();
                OperatingState.joyY = 0;
                OperatingState.initializedMouse = false;
                OperatingState.powerOff = false;
                OperatingState.playerOrigin = player.position();
                player.displayClientMessage(Component.literal("§aドローン操作モード開始 (終了: インベントリキー)"), true);
            }
            return net.minecraft.world.InteractionResult.SUCCESS;
        } else {
            if (level.isClientSide()) {
                player.displayClientMessage(Component.literal("§c周波数が登録されていません"), true);
            }
        }

        return net.minecraft.world.InteractionResult.PASS;
    }
}
