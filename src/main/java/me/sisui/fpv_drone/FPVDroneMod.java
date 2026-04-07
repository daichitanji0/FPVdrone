package me.sisui.fpv_drone;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import me.sisui.fpv_drone.network.DroneControlPayload;
import me.sisui.fpv_drone.network.DroneDeathPayload;
import me.sisui.fpv_drone.network.DroneJumpPayload;
import me.sisui.fpv_drone.network.DroneStatePayload;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.GameType;

/**
 * FPV Drone Modのメインクラス。
 * サーバー側と共通の初期化処理を担当します。
 */
public class FPVDroneMod implements ModInitializer {

	public static final String MOD_ID = "fpv_drone";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	/**
	 * ドローンの操作状態を維持するためのセッションクラス。
	 * プレイヤーがドローンを操作し始めた時の元の位置やゲームモードを保存します。
	 */
    public static class DroneSession {
        public final net.minecraft.world.level.GameType originalGameMode;
        public final double x, y, z;
        public final float yRot, xRot;
        
        public DroneSession(net.minecraft.world.level.GameType originalGameMode, double x, double y, double z, float yRot, float xRot) {
            this.originalGameMode = originalGameMode;
            this.x = x;
            this.y = y;
            this.z = z;
            this.yRot = yRot;
            this.xRot = xRot;
        }
    }
    public static final java.util.Map<java.util.UUID, DroneSession> droneSessions = new java.util.concurrent.ConcurrentHashMap<>();

	public static final Identifier DRONE_MOTOR_ID = Identifier.fromNamespaceAndPath(MOD_ID, "drone_motor");
	public static final SoundEvent DRONE_MOTOR_EVENT = SoundEvent.createVariableRangeEvent(DRONE_MOTOR_ID);

	public static final Identifier DRONE_HIT_ID = Identifier.fromNamespaceAndPath(MOD_ID, "drone_hit");
	public static final SoundEvent DRONE_HIT_EVENT = SoundEvent.createVariableRangeEvent(DRONE_HIT_ID);

	/**
	 * モッドの初期化処理。
	 * サウンド、アイテム、エンティティ、ネットワークパケットの登録を行います。
	 */
	@Override
	public void onInitialize() {
		LOGGER.info("FPV Drone Mod initializing...");

		Registry.register(BuiltInRegistries.SOUND_EVENT, DRONE_MOTOR_ID, DRONE_MOTOR_EVENT);
		Registry.register(BuiltInRegistries.SOUND_EVENT, DRONE_HIT_ID, DRONE_HIT_EVENT);

		ItemRegistry.registerItems();
		EntityRegistry.registerEntities();

		// ネットワークペイロード（パケットのデータ構造）の登録
		PayloadTypeRegistry.playC2S().register(DroneControlPayload.ID, DroneControlPayload.CODEC);
		PayloadTypeRegistry.playC2S().register(DroneJumpPayload.ID, DroneJumpPayload.CODEC);
		PayloadTypeRegistry.playC2S().register(DroneStatePayload.ID, DroneStatePayload.CODEC);
		PayloadTypeRegistry.playS2C().register(DroneDeathPayload.ID, DroneDeathPayload.CODEC);

		// ドローンの操作状態（操作開始/終了）をサーバーで受け取るレシーバー
		ServerPlayNetworking.registerGlobalReceiver(DroneStatePayload.ID, (payload, context) -> {
			context.server().execute(() -> {
				ServerPlayer sp = context.player();
				ServerLevel level = (ServerLevel) sp.level();
				if (payload.isOperating()) {
					// 全エンティティから周波数が一致するドローンを探す
					for (Entity entity : level.getAllEntities()) {
						if (entity instanceof DroneEntity drone && drone.getFrequency() == payload.frequency()) {
                            // 操作開始時にプレイヤーの現在位置と状態を保存（セッションがない場合のみ）
                            if (!droneSessions.containsKey(sp.getUUID())) {
                                droneSessions.put(sp.getUUID(), new DroneSession(
                                    sp.gameMode.getGameModeForPlayer(),
                                    sp.getX(), sp.getY(), sp.getZ(), sp.getYRot(), sp.getXRot()
                                ));
                            }
                            // プレイヤーを観戦モードにし、視点をドローンに固定する
                            sp.setGameMode(net.minecraft.world.level.GameType.SPECTATOR);
							sp.setCamera(drone);
							break;
						}
					}
				} else {
					// 操作終了時、視点を自分に戻す
					sp.setCamera(sp);
					
                    // 保存していたセッション情報を元にプレイヤーを元の位置とモードに戻す
                    DroneSession session = droneSessions.remove(sp.getUUID());
                    if (session != null) {
                        sp.setGameMode(session.originalGameMode);
                        sp.teleportTo((ServerLevel) sp.level(), session.x, session.y, session.z, java.util.Set.of(), session.yRot, session.xRot, false);
                    } else {
                        // セッションがない場合のフォールバック（バックアップ）
					    sp.teleportTo((ServerLevel) sp.level(), sp.getX(), sp.getY(), sp.getZ(), java.util.Set.of(), sp.getYRot(), sp.getXRot(), false);
                    }
				}
			});
		});

		// ドローンの操作入力（移動や旋回など）をサーバーで受け取るレシーバー
		ServerPlayNetworking.registerGlobalReceiver(DroneControlPayload.ID, (payload, context) -> {
			context.server().execute(() -> {
				int targetFreq = payload.frequency();
				ServerPlayer sp = context.player();
				ServerLevel level = (ServerLevel) sp.level();

				for (Entity entity : level.getAllEntities()) {
					if (entity instanceof DroneEntity drone) {
						if (drone.getFrequency() == targetFreq) {
							drone.setRemoteInput(payload.forward(), payload.sideways(),
									payload.jumping(), payload.sneaking(),
									payload.yRot(), payload.xRot(), payload.roll(),
									payload.powerOff(), payload.flightMode(), payload.cameraMode(), payload.boost(), sp);
						}
					}
				}
			});
		});

		// ドローンのジャンプ（特殊アクション）命令をサーバーで受け取るレシーバー
		ServerPlayNetworking.registerGlobalReceiver(DroneJumpPayload.ID, (payload, context) -> {
			context.server().execute(() -> {
				int targetFreq = payload.frequency();
				ServerLevel level = (ServerLevel) context.player().level();

				// 周波数が一致するドローンを展開し、ジャンプさせる
				for (Entity entity : level.getAllEntities()) {
					if (entity instanceof DroneEntity drone) {
						if (drone.getFrequency() == targetFreq) {
							drone.triggerJump(payload.jumpPower());
						}
					}
				}
			});
		});
	}
}
