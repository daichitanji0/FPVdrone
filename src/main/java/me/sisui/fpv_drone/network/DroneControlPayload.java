package me.sisui.fpv_drone.network;

import me.sisui.fpv_drone.FPVDroneMod;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * クライアントからサーバーへドローンの操作入力（前進、回転、カメラモードなど）を送信するためのペイロード。
 */
public record DroneControlPayload(int frequency, float forward, float sideways, boolean jumping, boolean sneaking, float yRot, float xRot, float roll, boolean powerOff, int flightMode, int cameraMode, boolean boost) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<DroneControlPayload> ID = new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(FPVDroneMod.MOD_ID, "drone_control"));
    
    public static final StreamCodec<FriendlyByteBuf, DroneControlPayload> CODEC = CustomPacketPayload.codec(
        DroneControlPayload::write,
        DroneControlPayload::new
    );

    public DroneControlPayload(FriendlyByteBuf buf) {
        this(buf.readInt(), buf.readFloat(), buf.readFloat(), buf.readBoolean(), buf.readBoolean(), buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readBoolean(), buf.readInt(), buf.readInt(), buf.readBoolean());
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeInt(frequency);
        buf.writeFloat(forward);
        buf.writeFloat(sideways);
        buf.writeBoolean(jumping);
        buf.writeBoolean(sneaking);
        buf.writeFloat(yRot);
        buf.writeFloat(xRot);
        buf.writeFloat(roll);
        buf.writeBoolean(powerOff);
        buf.writeInt(flightMode);
        buf.writeInt(cameraMode);
        buf.writeBoolean(boost);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return ID;
    }
}
