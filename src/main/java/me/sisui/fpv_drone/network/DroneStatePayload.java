package me.sisui.fpv_drone.network;

import me.sisui.fpv_drone.FPVDroneMod;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * プレイヤーのドローン操作状態（操作開始・終了）をクライアント・サーバー間で同期するためのペイロード。
 */
public record DroneStatePayload(boolean isOperating, int frequency) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<DroneStatePayload> ID = new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(FPVDroneMod.MOD_ID, "drone_state"));
    
    public static final StreamCodec<FriendlyByteBuf, DroneStatePayload> CODEC = CustomPacketPayload.codec(
        DroneStatePayload::write,
        DroneStatePayload::new
    );

    public DroneStatePayload(FriendlyByteBuf buf) {
        this(buf.readBoolean(), buf.readInt());
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeBoolean(isOperating);
        buf.writeInt(frequency);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return ID;
    }
}
