package me.sisui.fpv_drone.network;

import me.sisui.fpv_drone.FPVDroneMod;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * ドローンが破壊されたことをサーバーからクライアントへ通知するためのペイロード。
 */
public record DroneDeathPayload(int frequency) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<DroneDeathPayload> ID = new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(FPVDroneMod.MOD_ID, "drone_death"));
    
    public static final StreamCodec<FriendlyByteBuf, DroneDeathPayload> CODEC = CustomPacketPayload.codec(
        DroneDeathPayload::write,
        DroneDeathPayload::new
    );

    public DroneDeathPayload(FriendlyByteBuf buf) {
        this(buf.readInt());
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeInt(frequency);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return ID;
    }
}
