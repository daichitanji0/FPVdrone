package me.sisui.fpv_drone;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientEntityEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import me.sisui.fpv_drone.network.DroneControlPayload;
import me.sisui.fpv_drone.network.DroneDeathPayload;
import me.sisui.fpv_drone.network.DroneJumpPayload;
import me.sisui.fpv_drone.network.DroneStatePayload;
import net.minecraft.world.entity.Entity;

/**
 * FPV Drone Modのクライアント側エントリポイント。
 * クライアント固有の初期化、レンダリング、入力処理、物理予測を担当します。
 */
public class FPVDroneModClient implements ClientModInitializer {
	private boolean wasJumpD = false;
	private long lastJumpTime = 0;
	private DroneWindSound windSound = null;

	// --- 共有物理定数（サーバー側のDroneEntityと一致させる必要があります） ---
	private static final double H_ACCELERATION = 0.15;
	private static final double V_ACCELERATION = 0.25;
	private static final double H_FRICTION = 0.998;
	private static final double V_FRICTION = 0.98;
	private static final double H_MAX_SPEED = 50.0;
	private static final double V_MAX_SPEED = 17.5;

	// --- ロール飛行モデルの定数 ---
	private static final float ROLL_SENSITIVITY = 0.20f;  // マウスX移動量 -> ロール係数
	private static final float ROLL_DAMPING = 0.92f;      // 1ティックあたりの自動水平復帰率
	private static final float MAX_ROLL = 60.0f;          // 最大バンク角
	private static final float ROLL_TURN_RATE = 2.5f;     // ロール角 -> ヨー変化率
	private static final float RUDDER_RATE = 1.5f;        // A/Dキーによる直接ヨー旋回率

	/**
	 * マイクラの標準的なキー判定がGUI等で阻害される場合があるため、
	 * 生のキー入力状態を判定するヘルパーメソッド。
	 */
	private boolean isRawKeyDown(Minecraft client, net.minecraft.client.KeyMapping mapping) {
		if (mapping.isUnbound()) return false;
		com.mojang.blaze3d.platform.InputConstants.Key key =
				com.mojang.blaze3d.platform.InputConstants.getKey(mapping.saveString());
		int val = key.getValue();
		if (key.getType() == com.mojang.blaze3d.platform.InputConstants.Type.KEYSYM) {
			return com.mojang.blaze3d.platform.InputConstants.isKeyDown(client.getWindow(), val);
		} else if (key.getType() == com.mojang.blaze3d.platform.InputConstants.Type.MOUSE) {
			return org.lwjgl.glfw.GLFW.glfwGetMouseButton(client.getWindow().handle(), val) == 1;
		}
		return false;
	}

	/**
	 * 現在操作中の周波数に一致するドローンエンティティを検索します。
	 */
	private DroneEntity findOperatingDrone(Minecraft client) {
		if (client.level == null) return null;
		for (Entity entity : client.level.entitiesForRendering()) {
			if (entity instanceof DroneEntity drone
					&& drone.getFrequency() == OperatingState.operatingFrequency
					&& drone.isAlive()) {
				return drone;
			}
		}
		return null;
	}

	private void stopWindSound() {
		if (windSound != null) {
			windSound.stopSound();
			windSound = null;
		}
	}

	/**
	 * ドローンの操作モードを終了し、プレイヤーの状態をリセットします。
	 */
	private void exitDroneMode(Minecraft client) {
		// サーバーに操作終了を通知
		ClientPlayNetworking.send(new DroneStatePayload(false, OperatingState.operatingFrequency));
		OperatingState.isOperatingDrone = false;
		OperatingState.powerOff = false;
		OperatingState.cameraMode = 0;
		OperatingState.roll = 0;
		OperatingState.prevRoll = 0;
		OperatingState.smoothedLeftInput = 0f;
		OperatingState.smoothedRightInput = 0f;
		OperatingState.state = OperatingState.DroneState.LANDED;
		OperatingState.jumpPower = 0f;
		OperatingState.boostEnergy = 1.0f;
		OperatingState.isBoosting = false;
		OperatingState.sentDroneStatePacket = false;
		// 視点をプレイヤーに戻す
		client.setCameraEntity(client.player);
		client.options.setCameraType(net.minecraft.client.CameraType.FIRST_PERSON);
		stopWindSound();
	}

	@Override
	public void onInitializeClient() {
		// エンティティレンダラーの登録
		EntityRendererRegistry.register(EntityRegistry.DRONE, DroneEntityRenderer::new);

		// ドローンが読み込まれたときに風切り音（プロペラ音）を再生する設定
		net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientEntityEvents.ENTITY_LOAD.register((entity, world) -> {
			if (entity instanceof DroneEntity drone) {
				Minecraft.getInstance().getSoundManager().play(new DroneWindSound(drone));
			}
		});

		// ドローンの破壊通知パケットを受け取るレシーバー
		ClientPlayNetworking.registerGlobalReceiver(DroneDeathPayload.ID, (payload, context) -> {
			context.client().execute(() -> {
				if (OperatingState.isOperatingDrone && OperatingState.operatingFrequency == payload.frequency()) {
					if (context.client().player != null) {
						context.client().player.displayClientMessage(
								Component.literal("§cドローンが破壊されました"), true);
					}
					// 破壊時のノイズアニメーションを開始
					if (OperatingState.cameraMode != 3) {
						OperatingState.isDeathAnimating = true;
						OperatingState.deathAnimFrame = 0f;
						OperatingState.deathCameraTicks = 0;
					} else {
						exitDroneMode(context.client());
					}
				}
			});
		});

		// HUD（操作画面）のレンダリング
		HudRenderCallback.EVENT.register((drawContext, renderTickCounter) -> {
			Minecraft client = Minecraft.getInstance();
			if (OperatingState.isOperatingDrone) {
				int width = client.getWindow().getGuiScaledWidth();
				int height = client.getWindow().getGuiScaledHeight();
				
				// 着陸時：ジャンプのチャージバーを表示
				if (OperatingState.state == OperatingState.DroneState.LANDED && !OperatingState.powerOff) {
					int barWidth = 182;
					int barHeight = 5;
					int x = (width - barWidth) / 2;
					int y = height - 29;

					net.minecraft.resources.Identifier JUMP_BAR_BACKGROUND = net.minecraft.resources.Identifier.fromNamespaceAndPath("minecraft", "hud/jump_bar_background");
					net.minecraft.resources.Identifier JUMP_BAR_PROGRESS = net.minecraft.resources.Identifier.fromNamespaceAndPath("minecraft", "hud/jump_bar_progress");

					drawContext.blitSprite(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, JUMP_BAR_BACKGROUND, x, y, barWidth, barHeight);
					if (OperatingState.jumpPower > 0) {
						int fillWidth = (int)(barWidth * OperatingState.jumpPower);
						if (fillWidth > 0) {
							drawContext.blitSprite(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, JUMP_BAR_PROGRESS, 182, 5, 0, 0, x, y, fillWidth, barHeight);
						}
					}
				} 
				// 飛行時：ブースト残量バーを表示
				else if (OperatingState.state == OperatingState.DroneState.FLIGHT && !OperatingState.powerOff) {
					int barWidth = 182;
					int barHeight = 5;
					int x = (width - barWidth) / 2;
					int y = height - 42;

					net.minecraft.resources.Identifier XP_BAR_BACKGROUND = net.minecraft.resources.Identifier.fromNamespaceAndPath("minecraft", "hud/experience_bar_background");
					net.minecraft.resources.Identifier XP_BAR_PROGRESS = net.minecraft.resources.Identifier.fromNamespaceAndPath("minecraft", "hud/experience_bar_progress");

					drawContext.blitSprite(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, XP_BAR_BACKGROUND, x, y, barWidth, barHeight);
					if (OperatingState.boostEnergy > 0) {
						int fillWidth = (int)(barWidth * OperatingState.boostEnergy);
						if (fillWidth > 0) {
							drawContext.blitSprite(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, XP_BAR_PROGRESS, 182, 5, 0, 0, x, y, fillWidth, barHeight);
						}
					}
				}
			}

			if (OperatingState.isDeathAnimating) {
				int width = client.getWindow().getGuiScaledWidth();
				int height = client.getWindow().getGuiScaledHeight();
				// Use the path that worked (gave checkerboard/image)
				net.minecraft.resources.Identifier DISCONNECT_TEX = net.minecraft.resources.Identifier.fromNamespaceAndPath("fpv_drone", "textures/gui/tuusinkireta.png");
				
				int frame = (int) OperatingState.deathAnimFrame;
				if (frame > 23) frame = 23;

				int animFrame = (int) OperatingState.deathAnimFrame;
				if (animFrame > 23) animFrame = 23;

				// Using Matrix3x2fStack (2D) methods for scaling: pushMatrix/popMatrix
				drawContext.pose().pushMatrix();
				drawContext.pose().scale((float)width / 192f, (float)height / 108f);
				
				// Draw exactly ONE frame at 192x108 (which will be scaled to full screen)
				drawContext.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, DISCONNECT_TEX, 
						0, 0, (int)(animFrame * 192), 0, 192, 108, 4608, 108);
				
				drawContext.pose().popMatrix();
			}
		});

		// ===== START_CLIENT_TICK =====
		// バニラの処理が行われる前にキー入力をフックします。
		ClientTickEvents.START_CLIENT_TICK.register(client -> {
			if (!OperatingState.isOperatingDrone || client.player == null) return;

			if (client.isPaused()) return;

			// --- 操作終了判定：インベントリキーで操作を終了する ---
			while (client.options.keyInventory.consumeClick()) {
				client.player.displayClientMessage(
						Component.literal("§eドローン操作モードを終了しました"), true);
				exitDroneMode(client);
				return;
			}

			// --- 視点切り替え（F5キー）の独自処理 ---
			while (client.options.keyTogglePerspective.consumeClick()) {
				OperatingState.cameraMode = (OperatingState.cameraMode + 1) % 4;
				DroneEntity drone = findOperatingDrone(client);
                
                // ドローンが遠すぎる場合、プレイヤー視点(Mode 3)をスキップして空のバグを防ぐ
                if (OperatingState.cameraMode == 3 && drone != null && OperatingState.playerOrigin != null) {
                    if (drone.distanceToSqr(OperatingState.playerOrigin) > 64 * 64) {
                        client.player.displayClientMessage(Component.literal("§cドローンが遠すぎるためプレイヤー視点(Mode 3)は使用できません"), true);
                        OperatingState.cameraMode = 0; // Mode 0にスキップ
                    }
                }

				if (OperatingState.cameraMode == 0) {
					client.options.setCameraType(net.minecraft.client.CameraType.FIRST_PERSON); // 一人称
					if (drone != null) client.setCameraEntity(drone);
				} else if (OperatingState.cameraMode == 1) {
					client.options.setCameraType(net.minecraft.client.CameraType.THIRD_PERSON_BACK); // 三人称（後方）
					if (drone != null) client.setCameraEntity(drone);
				} else if (OperatingState.cameraMode == 2) {
					client.options.setCameraType(net.minecraft.client.CameraType.THIRD_PERSON_FRONT); // 三人称（前方）
					if (drone != null) client.setCameraEntity(drone);
				} else if (OperatingState.cameraMode == 3) {
					client.setCameraEntity(client.player); // プレイヤー視点からドローンを見る
					client.options.setCameraType(net.minecraft.client.CameraType.FIRST_PERSON);
				}
			}

			// --- プレイヤー本体の動作キーを無効化し、バニラのティック処理をバイパスする ---
			client.options.keyUp.setDown(false);
			client.options.keyDown.setDown(false);
			client.options.keyLeft.setDown(false);
			client.options.keyRight.setDown(false);
			client.options.keyJump.setDown(false);
			client.options.keyShift.setDown(false);

			while (client.options.keyUp.consumeClick()) {}
			while (client.options.keyDown.consumeClick()) {}
			while (client.options.keyLeft.consumeClick()) {}
			while (client.options.keyRight.consumeClick()) {}
			while (client.options.keyJump.consumeClick()) {}
			while (client.options.keyShift.consumeClick()) {}
			while (client.options.keyDrop.consumeClick()) {}
			while (client.options.keySwapOffhand.consumeClick()) {}
		});

		// ===== END_CLIENT_TICK =====
		// ドローンの操作、ロールベースの飛行挙動、予測、サウンド、パケット送信のメイン処理。
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			// 破壊時の砂嵐アニメーション中
			if (OperatingState.isDeathAnimating) {
				OperatingState.deathAnimFrame += OperatingState.DEATH_ANIM_TICK_SPEED;
				if (OperatingState.deathAnimFrame >= 23.9f) {
					OperatingState.deathAnimFrame = 23.9f; 
					if (OperatingState.deathCameraTicks == 0) OperatingState.deathCameraTicks = 10; // 0.5秒停止
					OperatingState.deathCameraTicks--;
					if (OperatingState.deathCameraTicks <= 0) {
						OperatingState.isDeathAnimating = false;
						exitDroneMode(client);
					}
				}
				return;
			}

			if (OperatingState.deathCameraTicks > 0) {
				OperatingState.deathCameraTicks--;
				if (OperatingState.deathCameraTicks <= 0) {
					exitDroneMode(client);
				}
				return;
			}

			// 操作中でないかプレイヤーがnullの場合は終了
			if (!OperatingState.isOperatingDrone || client.player == null) {
				if (!OperatingState.isOperatingDrone) stopWindSound();
				OperatingState.initializedMouse = false;
				return;
			}
			// 一時停止中やマウスがグラブされていない場合は入力を受け付けない
			if (client.isPaused() || !client.mouseHandler.isMouseGrabbed()) {
				OperatingState.initializedMouse = false;
				return;
			}

			DroneEntity drone = findOperatingDrone(client);
			
			// ドローンが見つからない場合のタイムアウト処理
			if (drone == null) {
				OperatingState.lostDroneTicks++;
				if (OperatingState.lostDroneTicks > 40) {
					client.player.displayClientMessage(Component.literal("§cドローンとの通信が途絶えました"), true);
					exitDroneMode(client);
					return;
				}
			} else {
				OperatingState.lostDroneTicks = 0;
			}


			// --- カメラ設定 (毎ティック強制適用) ---
			if (drone != null && OperatingState.cameraMode != 3) {
				if (!(client.getCameraEntity() instanceof DroneEntity dronecam
						&& dronecam.getFrequency() == OperatingState.operatingFrequency)) {
					client.setCameraEntity(drone);
				}
			} else if (OperatingState.cameraMode == 3) {
				if (client.getCameraEntity() != client.player) {
					client.setCameraEntity(client.player);
					client.options.setCameraType(net.minecraft.client.CameraType.FIRST_PERSON);
				}
			}
			
			OperatingState.activeDrone = drone;

			// Send DroneStatePayload(true) once when we first find the drone
			if (drone != null && !OperatingState.sentDroneStatePacket) {
				ClientPlayNetworking.send(new DroneStatePayload(true, OperatingState.operatingFrequency));
				OperatingState.sentDroneStatePacket = true;
			}

			boolean onGround = drone != null && drone.onGround();

			// --- 状態遷移の管理（着陸・離陸の判定） ---
			if (drone != null) {
				if (onGround && !OperatingState.wasOnGround && OperatingState.state != OperatingState.DroneState.LANDED) {
					OperatingState.state = OperatingState.DroneState.LANDED;
					OperatingState.joyX = 0; // ロールを停止
					OperatingState.targetRoll = 0;
					client.player.displayClientMessage(net.minecraft.network.chat.Component.literal("§a[着陸] 着陸モード"), true);
				} else if (!onGround && OperatingState.state == OperatingState.DroneState.TAKING_OFF && drone.getDeltaMovement().y <= 0) {
					OperatingState.state = OperatingState.DroneState.FLIGHT;
					client.player.displayClientMessage(net.minecraft.network.chat.Component.literal("§b[離陸] 飛行制御を有効化"), true);
				}
				OperatingState.wasOnGround = onGround;
			}

			if (OperatingState.powerOff) {
				// Prevent forcing landed mid-air. Wait for ground naturally.
			}

			// --- Key Binding Polling ---
			boolean upD = isRawKeyDown(client, client.options.keyUp);
			boolean downD = isRawKeyDown(client, client.options.keyDown);
			boolean leftD = isRawKeyDown(client, client.options.keyLeft);
			boolean rightD = isRawKeyDown(client, client.options.keyRight);
			boolean jumpD = isRawKeyDown(client, client.options.keyJump);
			OperatingState.isJumpD = jumpD;
			boolean sneakD = isRawKeyDown(client, client.options.keyShift);
			boolean useD = isRawKeyDown(client, client.options.keyUse); // right click for boost
			boolean attackD = isRawKeyDown(client, client.options.keyAttack); // left click

            // --- Input Vibration Effect (fires on key-down edge) ---
            if (OperatingState.playerOrigin != null && drone != null && !OperatingState.powerOff) {
                boolean anyNewKeyDown = false;
                if (upD && !OperatingState.prevUpD) anyNewKeyDown = true;
                if (downD && !OperatingState.prevDownD) anyNewKeyDown = true;
                if (leftD && !OperatingState.prevLeftD) anyNewKeyDown = true;
                if (rightD && !OperatingState.prevRightD) anyNewKeyDown = true;
                if (jumpD && !OperatingState.prevJumpKeyD) anyNewKeyDown = true;
                if (sneakD && !OperatingState.prevSneakD) anyNewKeyDown = true;
                if (attackD && !OperatingState.prevAttackD) anyNewKeyDown = true;
                
                if (anyNewKeyDown) {
                    int travelTime = (int) (Math.max(5, Math.min(60, Math.sqrt(drone.distanceToSqr(OperatingState.playerOrigin)))));
                    net.minecraft.world.level.gameevent.EntityPositionSource dest = new net.minecraft.world.level.gameevent.EntityPositionSource(drone, 0.2f);
                    net.minecraft.core.particles.VibrationParticleOption particle = new net.minecraft.core.particles.VibrationParticleOption(dest, travelTime);
                    client.level.addParticle(
                        particle,
                        OperatingState.playerOrigin.x,
                        OperatingState.playerOrigin.y + 1.0,
                        OperatingState.playerOrigin.z,
                        0, 0, 0
                    );
                }
                OperatingState.prevUpD = upD;
                OperatingState.prevDownD = downD;
                OperatingState.prevLeftD = leftD;
                OperatingState.prevRightD = rightD;
                OperatingState.prevJumpKeyD = jumpD;
                OperatingState.prevSneakD = sneakD;
                OperatingState.prevAttackD = attackD;
            }

			float forward = (upD ? 1.0F : 0.0F) - (downD ? 1.0F : 0.0F);
			float sideways = (OperatingState.cameraMode == 3) ? ((rightD ? 1.0F : 0.0F) - (leftD ? 1.0F : 0.0F)) : 0.0f;
			boolean up = jumpD;
			boolean down = sneakD;

			// --- バーチャルジョイスティックとヨー・ロールの計算 ---
			if (OperatingState.cameraMode != 3 && drone != null) {
				
				long handle = client.getWindow().handle();
				double[] xPos = new double[1];
				double[] yPos = new double[1];
				org.lwjgl.glfw.GLFW.glfwGetCursorPos(handle, xPos, yPos);
				
				// マウスの移動量をキャプチャ
				if (!OperatingState.initializedMouse) {
					OperatingState.initializedMouse = true;
					OperatingState.lastMouseX = xPos[0];
					OperatingState.lastMouseY = yPos[0];
				} else {
					float mouseXDelta = (float)(xPos[0] - OperatingState.lastMouseX) * 0.15f;
					float mouseYDelta = (float)(yPos[0] - OperatingState.lastMouseY) * 0.15f;
					
					OperatingState.lastMouseX = xPos[0];
					OperatingState.lastMouseY = yPos[0];

					if (OperatingState.state == OperatingState.DroneState.LANDED) {
						// 着陸モード：カメラのみ回転し、ドローンは移動しない
						OperatingState.droneYaw += mouseXDelta;
						OperatingState.targetPitch += mouseYDelta;
						
						OperatingState.targetRoll = 0f; // 見た目のロールをゼロに固定
						OperatingState.joyX = 0;
						OperatingState.joyY = 0;
						
					} else {
						// 飛行モード：バーチャルジョイスティックによる制御
						OperatingState.joyX += mouseXDelta;
						OperatingState.joyY += mouseYDelta; // 前に倒すとマイナス(Minecraft上では機首が下がる)
						
						// ホイールクリックでジョイスティックを中央にリセット
						if (org.lwjgl.glfw.GLFW.glfwGetMouseButton(client.getWindow().handle(), org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_MIDDLE) == org.lwjgl.glfw.GLFW.GLFW_PRESS) {
							OperatingState.joyX = 0;
							OperatingState.joyY = 0;
						}

						// ジョイスティックの位置から目標のロールとピッチを導出
						float rawTargetRoll = OperatingState.joyX * ROLL_SENSITIVITY; 
						float rawTargetPitch = -OperatingState.joyY * 0.5f;
						
						OperatingState.targetRoll = rawTargetRoll;
						OperatingState.targetPitch = rawTargetPitch;
					}
				}

				if (OperatingState.targetRoll > MAX_ROLL) OperatingState.targetRoll = MAX_ROLL;
				if (OperatingState.targetRoll < -MAX_ROLL) OperatingState.targetRoll = -MAX_ROLL;
				if (OperatingState.targetPitch > 90.0f) OperatingState.targetPitch = 90.0f;
				if (OperatingState.targetPitch < -90.0f) OperatingState.targetPitch = -90.0f;

				// --- A/Dキーによるヨー旋回とロール打ち消しロジック ---
				float easingSpeed = 0.1f;
				OperatingState.smoothedLeftInput += leftD ? easingSpeed : -easingSpeed;
				OperatingState.smoothedLeftInput = Math.max(0f, Math.min(1f, OperatingState.smoothedLeftInput));

				OperatingState.smoothedRightInput += rightD ? easingSpeed : -easingSpeed;
				OperatingState.smoothedRightInput = Math.max(0f, Math.min(1f, OperatingState.smoothedRightInput));

				if (OperatingState.state == OperatingState.DroneState.FLIGHT && !OperatingState.powerOff) {
					float yawAccel = 0;
					float leftAmt = OperatingState.smoothedLeftInput;
					float rightAmt = OperatingState.smoothedRightInput;

					if (leftAmt > 0 || rightAmt > 0) {
						float rawRoll = OperatingState.targetRoll;
						// スティックを倒しているほど急旋回になるように指数スケーリング
						float stickFactor = rawRoll * (1.0f + Math.abs(rawRoll) * 0.015f);
						float activation = Math.max(leftAmt, rightAmt);
						
						yawAccel = stickFactor * RUDDER_RATE * 0.12f * activation;
						
						// A/D入力をロールと逆方向に当てることで、水平旋回（フラットスピン）を行う
						if (rawRoll > 0 && leftAmt > 0) {
							OperatingState.targetRoll = net.minecraft.util.Mth.lerp(leftAmt, rawRoll, 0.0f);
						} else if (rawRoll < 0 && rightAmt > 0) {
							OperatingState.targetRoll = net.minecraft.util.Mth.lerp(rightAmt, rawRoll, 0.0f);
						}
					}
					OperatingState.droneYaw += yawAccel;
				}

				// Smoothly interpolate roll
				OperatingState.prevRoll = OperatingState.roll;
				OperatingState.roll += (OperatingState.targetRoll - OperatingState.roll) * 0.2f;

			} else if (OperatingState.cameraMode == 3) {
				OperatingState.droneYaw = client.player.getYRot();
				
				float m3TargetPitch = 0f;
				float m3TargetRoll = 0f;
				if (upD) m3TargetPitch = 30f; // W -> forward (nose down)
				if (downD) m3TargetPitch = -30f; // S -> backward (nose up)
				if (leftD) m3TargetRoll = -30f;
				if (rightD) m3TargetRoll = 30f;
				
				OperatingState.targetPitch += (m3TargetPitch - OperatingState.targetPitch) * 0.15f;
				OperatingState.targetRoll += (m3TargetRoll - OperatingState.targetRoll) * 0.15f;
				
				OperatingState.prevRoll = OperatingState.roll;
				OperatingState.roll += (OperatingState.targetRoll - OperatingState.roll) * 0.2f;
			}
			
			// --- 電源状態の検知（シングルクリックでオン、ダブルクリックでオフ） ---
			if (jumpD && !OperatingState.prevJumpD) {
				long now = System.currentTimeMillis();
                
                if (OperatingState.powerOff) {
                    // シングルクリックで電源オン
                    OperatingState.powerOff = false;
                    client.player.displayClientMessage(Component.literal("§a\u23FB POWER ON"), true);
                } else {
                    // 300ms以内の連打で電源オフ
                    if (now - OperatingState.lastJumpPressTime < 300) {
                        OperatingState.powerOff = true;
                        client.player.displayClientMessage(Component.literal("§c\u23FB POWER OFF"), true);
                        OperatingState.jumpChargeTicks = 0; // チャージ中のジャンプをキャンセル
                    }
                }
				OperatingState.lastJumpPressTime = now;
			}
			OperatingState.prevJumpD = jumpD;

			// --- ジャンプ挙動（着陸時のみ） ---
			if (OperatingState.state == OperatingState.DroneState.LANDED && !OperatingState.powerOff) {
				if (jumpD) {
					// チャージ処理（最長3秒）
					OperatingState.jumpChargeTicks++;
					if (OperatingState.jumpChargeTicks <= 30) {
						OperatingState.jumpPower = OperatingState.jumpChargeTicks / 30.0f;
					} else if (OperatingState.jumpChargeTicks <= 60) {
						OperatingState.jumpPower = 1.0f - ((OperatingState.jumpChargeTicks - 30) / 30.0f) * 0.666f;
					} else {
						OperatingState.jumpPower = 0.334f;
					}
				} else if (OperatingState.jumpChargeTicks > 0) {
					// ジャンプ実行
					float multiplier = 0.5f + OperatingState.jumpPower * 1.0f; // 最小0.5, 最大1.5
					
					ClientPlayNetworking.send(new DroneJumpPayload(OperatingState.operatingFrequency, multiplier));
					
					if (drone != null) {
						drone.setDeltaMovement(drone.getDeltaMovement().add(0, multiplier, 0));
						client.level.addParticle(net.minecraft.core.particles.ParticleTypes.EXPLOSION, 
							drone.getX(), drone.getY(), drone.getZ(), 0, 0, 0);
						client.player.displayClientMessage(net.minecraft.network.chat.Component.literal("§e[テイクオフ！]"), true);
					}
					
					OperatingState.state = OperatingState.DroneState.TAKING_OFF;
					OperatingState.jumpPower = 0f;
					OperatingState.jumpChargeTicks = 0;
				}
			} else {
				OperatingState.jumpPower = 0f;
				OperatingState.jumpChargeTicks = 0;
			}

			// --- ブーストロジック ---
			if (OperatingState.state == OperatingState.DroneState.FLIGHT && !OperatingState.powerOff) {
				if (useD && OperatingState.boostEnergy > 0) {
					OperatingState.isBoosting = true;
					OperatingState.boostEnergy -= 0.015f; // 減少
					if (OperatingState.boostEnergy < 0) OperatingState.boostEnergy = 0;
				} else {
					OperatingState.isBoosting = false;
					if (OperatingState.boostEnergy <= 0.0f || !useD) {
						// 推約1.5秒でフルチャージ
						OperatingState.boostEnergy += 0.0333f;
						if (OperatingState.boostEnergy > 1.0f) OperatingState.boostEnergy = 1.0f;
					}
				}
			} else {
				OperatingState.isBoosting = false;
				OperatingState.boostEnergy += 0.0333f;
				if (OperatingState.boostEnergy > 1.0f) OperatingState.boostEnergy = 1.0f;
			}

			float yRot = OperatingState.droneYaw;
			float xRot = OperatingState.targetPitch;

			// --- クライアント側物理予測（ラグ軽減のためサーバーとほぼ同じ計算を先行して行う） ---
			if (drone != null) {
				// 回転の同期
				drone.setYRot(yRot);
				drone.yBodyRot = yRot;
				drone.yHeadRot = yRot;
				drone.setXRot(xRot);

				// 移動の同期
				net.minecraft.world.phys.Vec3 vel = drone.getDeltaMovement();
				double vx = vel.x, vy = vel.y, vz = vel.z;

				if (OperatingState.state == OperatingState.DroneState.FLIGHT && !OperatingState.powerOff) {
					// 推力ベクトルによる移動予測
					float pitchRad = (float) Math.toRadians(OperatingState.targetPitch);
					float yawRad = (float) Math.toRadians(OperatingState.droneYaw);
					float rollRad = (float) Math.toRadians(OperatingState.targetRoll);

					double hoverThrust = V_ACCELERATION - 0.02; // サーバー側の重力減衰と一致させる
					double ax = 0, ay = 0, az = 0;

					if (OperatingState.cameraMode == 3) {
						// プレイヤー視点モード：特殊な単純移動制御
						double speed = H_ACCELERATION * 0.8; 
						if (OperatingState.isBoosting) speed *= 2.0;

						ax = -Math.sin(yawRad) * forward * speed - Math.cos(yawRad) * sideways * speed;
						az = Math.cos(yawRad) * forward * speed - Math.sin(yawRad) * sideways * speed;
						
						if (up) ay += 0.05;
						if (down) ay -= 0.05;
					} else {
						// 物理ベースモード：機体の傾きに応じた推力計算
						double liftMod = 0;

						if (forward > 0 && OperatingState.state == OperatingState.DroneState.FLIGHT) {
							liftMod = -0.005 * OperatingState.targetPitch;
						} else if (forward < 0 && OperatingState.state == OperatingState.DroneState.FLIGHT) {
							liftMod = 0.005 * OperatingState.targetPitch;
						}

						if (up && OperatingState.state == OperatingState.DroneState.FLIGHT) liftMod += 0.05;
						if (down && OperatingState.state == OperatingState.DroneState.FLIGHT) liftMod -= 0.05;

						double finalThrust = hoverThrust + liftMod;

						org.joml.Vector3f upVec = new org.joml.Vector3f(0f, 1f, 0f);
						org.joml.Vector3f forwardVec = new org.joml.Vector3f(0f, 0f, 1f);
						org.joml.Quaternionf q = new org.joml.Quaternionf()
								.rotationY(-yawRad)
								.rotateX(pitchRad)
								.rotateZ(rollRad);
						upVec.rotate(q);
						forwardVec.rotate(q);

						ax = (double)upVec.x * finalThrust;
						ay = (double)upVec.y * finalThrust - hoverThrust; 
						az = (double)upVec.z * finalThrust;

						if (OperatingState.isBoosting) {
							double boostPower = 0.2;
							ax += (double)forwardVec.x * boostPower;
							ay += (double)forwardVec.y * boostPower;
							az += (double)forwardVec.z * boostPower;
						}
					}

					vx = (vx + ax) * H_FRICTION;
					vy = (vy + ay) * V_FRICTION;
					vz = (vz + az) * H_FRICTION;
				} else if (OperatingState.state == OperatingState.DroneState.TAKING_OFF) {
					// 自由落下、または慣性移動
					double ay = -V_ACCELERATION;
					vy = (vy + ay) * V_FRICTION;
					vx *= H_FRICTION;
					vz *= H_FRICTION;
				} else if (OperatingState.state == OperatingState.DroneState.LANDED) {
					vx = 0;
					vz = 0;
					// バニラの重力と衝突判定に任せるが、沈み込みを防ぐためにvyを補正
					if (onGround && vy < 0) {
						vy = 0;
					}
					// 地面にいる場合はバニラの処理に任せる
					if (onGround) {
					    drone.setDeltaMovement(0, drone.getDeltaMovement().y, 0);
					    return; 
					}
				}

				double horizontalSpeedSq = vx * vx + vz * vz;
				if (horizontalSpeedSq > H_MAX_SPEED * H_MAX_SPEED) {
					double scale = H_MAX_SPEED / Math.sqrt(horizontalSpeedSq);
					vx *= scale;
					vz *= scale;
				}
				if (vy > V_MAX_SPEED)
					vy = V_MAX_SPEED;
				if (vy < -V_MAX_SPEED)
					vy = -V_MAX_SPEED;

				drone.setDeltaMovement(vx, vy, vz);
			}

			// --- 操作中のプレイヤー本体を動かさないようにする ---
			client.player.setDeltaMovement(0, client.player.getDeltaMovement().y, 0);
			client.player.setSpeed(0.0F);

			// --- サーバーに操作入力パケットを送信 ---
			ClientPlayNetworking.send(new DroneControlPayload(
					OperatingState.operatingFrequency,
					forward, sideways, up, down, yRot, xRot, OperatingState.targetRoll,
					OperatingState.powerOff, OperatingState.state.ordinal(), OperatingState.cameraMode, OperatingState.isBoosting));
		});
	}
}
