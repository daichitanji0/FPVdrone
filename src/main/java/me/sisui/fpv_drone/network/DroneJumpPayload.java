package me.sisui.fpv_drone.network;

import me.sisui.fpv_drone.FPVDroneMod;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * クライアントからサーバーへドローンのジャンプ（急上昇）操作を送信するためのペイロード。
 */
public record DroneJumpPayload(int frequency, float jumpPower) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<DroneJumpPayload> ID = new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(FPVDroneMod.MOD_ID, "drone_jump"));
    
    public static final StreamCodec<FriendlyByteBuf, DroneJumpPayload> CODEC = CustomPacketPayload.codec(
        DroneJumpPayload::write,
        DroneJumpPayload::new
    );

    public DroneJumpPayload(FriendlyByteBuf buf) {
        this(buf.readInt(), buf.readFloat());
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeInt(frequency);
        buf.writeFloat(jumpPower);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return ID;
    }
}
