package me.sisui.fpv_drone;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.item.component.CustomData;

/**
 * ドローンのアイテムクラス。
 * ブロックに対して使用した際に、ドローンエンティティをワールドにスポーンさせます。
 */
public class DroneItem extends Item {
    public DroneItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        // クライアント側では何もしない
        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }

        BlockPos clickedPos = context.getClickedPos();
        // クリックされたブロックの面に対応する隣接ブロックの座標をスポーン位置とする
        BlockPos spawnPos = clickedPos.relative(context.getClickedFace());

        // スポーン位置の上方10ブロック分に障害物がないか確認
        boolean isClear = true;
        for (int i = 0; i < 10; i++) {
            BlockPos checkPos = spawnPos.above(i);
            BlockState state = level.getBlockState(checkPos);
            if (!state.isAir()) {
                isClear = false;
                break;
            }
        }

        // 十分なスペースがない場合はスポーンをキャンセルし、メッセージを表示
        if (!isClear) {
            if (context.getPlayer() != null) {
                context.getPlayer().displayClientMessage(Component.literal("§c上方に十分なスペースがありません"), false);
            }
            return InteractionResult.FAIL;
        }

        // ドローンエンティティを生成
        DroneEntity drone = EntityRegistry.DRONE.create(level, EntitySpawnReason.COMMAND);
        if (drone != null) {
            // スポーン位置と向きを設定（プレイヤーの向いている方向に合わせる）
            drone.setPos(spawnPos.getX() + 0.5, spawnPos.getY(), spawnPos.getZ() + 0.5);
            drone.setYRot(context.getRotation());

            // アイテムのNBTデータから周波数を読み込む（設定されていない場合はランダムに生成）
            CustomData cd = context.getItemInHand().getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY);
            int freq = cd.copyTag().getInt("DroneFrequency").orElse(0);
            if (freq == 0) {
                freq = level.random.nextInt(999998) + 1;
            }
            drone.setFrequency(freq);

            // ワールドにエンティティを追加
            level.addFreshEntity(drone);

            // 使用したアイテムを1つ消費
            context.getItemInHand().shrink(1);
        }

        return InteractionResult.CONSUME;
    }
}
