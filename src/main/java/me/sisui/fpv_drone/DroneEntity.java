package me.sisui.fpv_drone;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animatable.manager.AnimatableManager;
import software.bernie.geckolib.animation.*;
import software.bernie.geckolib.util.GeckoLibUtil;

/**
 * ドローンエンティティのメインクラス
 * 飛行物理、制御入力の受領、アニメーション同期などを担当する
 */
public class DroneEntity extends PathfinderMob implements GeoEntity {
    public Player currentOperator = null;

    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);
    // データトラッカー：クライアントとサーバーで同期される変数
    private static final EntityDataAccessor<Integer> FREQUENCY = SynchedEntityData.defineId(DroneEntity.class,
            EntityDataSerializers.INT);
    private static final EntityDataAccessor<Float> DRONE_ROLL = SynchedEntityData.defineId(DroneEntity.class,
            EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Integer> FLIGHT_MODE = SynchedEntityData.defineId(DroneEntity.class,
            EntityDataSerializers.INT);
    private static final EntityDataAccessor<Boolean> POWER_OFF = SynchedEntityData.defineId(DroneEntity.class,
            EntityDataSerializers.BOOLEAN);
    private ChunkPos lastForcedChunk = null;

    // リモート操作入力の状態（サーバー側で保持）
    public float remoteForward = 0.0f;
    public float remoteSideways = 0.0f;
    public boolean remoteUp = false;
    public boolean remoteSneaking = false;
    public int remoteFlightMode = 0; // 0=着陸中, 1=離陸中, 2=飛行中
    public boolean remotePowerOff = false;
    public float targetYRot = 0.0f;
    public float targetXRot = 0.0f;
    public float targetRoll = 0.0f;
    public int remoteControlTicks = 0;
    public int remoteCameraMode = 0;
    public boolean remoteBoost = false;

    // 音響演出用の物理トラッキング
    private boolean wasOnGround = false;
    private boolean wasHorizontalCollision = false;
    public float propellerSpeedMul = 0.2f; // プロペラの回転速度（0.0=停止, 1.0=通常, 2.0=高速）

    public DroneEntity(EntityType<? extends PathfinderMob> type, Level level) {
        super(type, level);
    }

    @Override
    public boolean removeWhenFarAway(double distanceToClosestPlayer) {
        return false; // Never despawn — drone is player-placed
    }

    @Override
    public boolean isPersistenceRequired() {
        return true; // Always persist
    }

    @Override
    public float getViewXRot(float partialTick) {
        if (this.level().isClientSide() && me.sisui.fpv_drone.OperatingState.isOperatingDrone
                && me.sisui.fpv_drone.OperatingState.activeDrone == this) {
            if (me.sisui.fpv_drone.OperatingState.cameraMode == 1
                    || me.sisui.fpv_drone.OperatingState.cameraMode == 2) {
                return 10.0f; // ドローンの三人称視点時に少し見下ろす角度に固定
            }
        }
        return super.getViewXRot(partialTick);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(FREQUENCY, 0);
        builder.define(DRONE_ROLL, 0.0f);
        builder.define(FLIGHT_MODE, 0);
        builder.define(POWER_OFF, false);
    }

    public void setFrequency(int freq) {
        this.entityData.set(FREQUENCY, freq);
    }

    public int getFrequency() {
        return this.entityData.get(FREQUENCY);
    }

    public float getRoll() {
        return this.entityData.get(DRONE_ROLL);
    }

    public void setRoll(float roll) {
        this.entityData.set(DRONE_ROLL, roll);
    }

    public int getFlightMode() {
        return this.entityData.get(FLIGHT_MODE);
    }

    public void setFlightMode(int mode) {
        this.entityData.set(FLIGHT_MODE, mode);
    }

    public boolean isPowerOff() {
        return this.entityData.get(POWER_OFF);
    }

    public void setPowerOff(boolean off) {
        this.entityData.set(POWER_OFF, off);
    }

    // ドローンの識別名を生成するための定数
    // private static final int PREFIX_COUNT = 31; // 形容詞の数
    // private static final int SUFFIX_COUNT = 31; // 名詞の数

    /**
     * ドローンの周波数に基づいてランダムな組み合わせの名前を生成...
     * するはずだったけどダサかったからいったん消し
     */
    /*
    public Component getDroneDisplayName() {
        int freq = this.getFrequency();
        java.util.Random nameRnd = new java.util.Random(freq);

        int preIdx = nameRnd.nextInt(PREFIX_COUNT) + 1;
        int sufIdx = nameRnd.nextInt(SUFFIX_COUNT) + 1;

        String preKey = "drone_name.prefix." + preIdx;
        String sufKey = "drone_name.suffix." + sufIdx;

        return Component.empty()
                .append(Component.translatable(preKey))
                .append(" ")
                .append(Component.translatable(sufKey));
    }
    */

    private AnimationController<DroneEntity> propellerController;

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        // プロペラのアニメーション制御
        propellerController = new AnimationController<DroneEntity>("propeller_controller", 0, state -> {
            if (this.remotePowerOff && this.propellerSpeedMul <= 0.01f) {
                return software.bernie.geckolib.animation.object.PlayState.STOP;
            }
            if (propellerController != null) {
                propellerController.setAnimationSpeed(this.propellerSpeedMul);
            }
            return state.setAndContinue(RawAnimation.begin().thenLoop("spin_props"));
        });
        controllers.add(propellerController);
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return cache;
    }

    public static AttributeSupplier.Builder createDroneAttributes() {
        return PathfinderMob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 10.0)
                .add(Attributes.MOVEMENT_SPEED, 0.25);
    }

    public int crashDeathTimer = -1;

    @Override
    public void tick() {
        // 衝突による大破タイマーの処理
        if (this.crashDeathTimer > 0) {
            this.crashDeathTimer--;
            if (this.crashDeathTimer == 0) {
                if (!this.level().isClientSide()) {
                    this.remove(net.minecraft.world.entity.Entity.RemovalReason.KILLED);
                    if (this.getFrequency() > 0) {
                        me.sisui.fpv_drone.network.DroneDeathPayload payload = new me.sisui.fpv_drone.network.DroneDeathPayload(
                                this.getFrequency());
                        if (this.level() instanceof net.minecraft.server.level.ServerLevel sl) {
                            for (net.minecraft.server.level.ServerPlayer p : sl.players()) {
                                net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(p, payload);
                            }
                        }
                    }
                }
            }
            this.setDeltaMovement(0, 0, 0); // 爆発演出中（タイマー中）は動かない
            super.tick();
            return;
        }
        // 落下ダメージの無効化
        this.resetFallDistance();
        // 衝突検知のためにティック前の速度を保存
        net.minecraft.world.phys.Vec3 preTickVel = this.getDeltaMovement();
        super.tick();

        if (!this.level().isClientSide()) {
            // High-speed block collision -> drone death
            if (this.horizontalCollision || this.verticalCollision) {
                // Check if the pre-collision velocity was dangerously high
                if (Math.abs(preTickVel.x) > 0.8 || Math.abs(preTickVel.z) > 0.8 || preTickVel.y < -0.8) {
                    this.crashDeathTimer = 4; // 4 ticks of visual delay
                    this.playSound(FPVDroneMod.DRONE_HIT_EVENT, 1.0f, 1.0f);
                }
            }

            ServerLevel sl = (ServerLevel) this.level();
            ChunkPos currentChunk = this.chunkPosition();
            if (lastForcedChunk == null || !lastForcedChunk.equals(currentChunk)) {
                if (lastForcedChunk != null) {
                    sl.setChunkForced(lastForcedChunk.x, lastForcedChunk.z, false);
                }
                sl.setChunkForced(currentChunk.x, currentChunk.z, true);
                lastForcedChunk = currentChunk;
            }

            this.remoteControlTicks++;

            // --- 操作プレイヤーの同期とチャンクロード維持 ---
            if (this.currentOperator instanceof net.minecraft.server.level.ServerPlayer sp) {
                FPVDroneMod.DroneSession session = FPVDroneMod.droneSessions.get(sp.getUUID());
                if (session != null) {
                    if (this.remoteCameraMode == 3) {
                        // プレイヤー視点モード：観戦者本体を元の場所に戻す
                        if (sp.getCamera() != sp) sp.setCamera(sp);
                        if (sp.distanceToSqr(session.x, session.y, session.z) > 0.5) {
                            sp.teleportTo((ServerLevel) sp.level(), session.x, session.y, session.z, java.util.Set.of(), session.yRot, session.xRot, false);
                        }
                    } else {
                        // ドローン視点モード：観戦者本体をドローンに同化させてチャンクロードを維持
                        if (sp.getCamera() != this) sp.setCamera(this);
                        if (sp.distanceToSqr(this.getX(), this.getY(), this.getZ()) > 9.0) {
                            sp.teleportTo((ServerLevel) sp.level(), this.getX(), this.getY(), this.getZ(), java.util.Set.of(), sp.getYRot(), sp.getXRot(), false);
                        }
                    }
                }
            }

            // --- 演出：着地音 ---
            if (this.onGround() && !this.wasOnGround) {
                this.playSound(net.minecraft.sounds.SoundEvents.GENERIC_SMALL_FALL, 0.5f, 1.2f);
            }
            this.wasOnGround = this.onGround();

            // --- 演出：接触音 ---
            if (this.horizontalCollision && !this.wasHorizontalCollision) {
                float speed = (float) this.getDeltaMovement().length();
                if (speed > 0.05f) {
                    float pitch = 0.8f + this.random.nextFloat() * 0.4f;
                    this.playSound(FPVDroneMod.DRONE_HIT_EVENT, 0.7f, pitch);
                }
            }
            this.wasHorizontalCollision = this.horizontalCollision;

            // --- 演出：ブースト時のパーティクル ---
            if (this.remoteBoost && this.tickCount % 2 == 0) {
                ServerLevel sll = (ServerLevel) this.level();
                float yawRad = (float) Math.toRadians(this.getYRot());
                double ox = Math.sin(yawRad) * 0.4;
                double oz = -Math.cos(yawRad) * 0.4;
                sll.sendParticles(net.minecraft.core.particles.ParticleTypes.WITCH,
                        this.getX() + ox, this.getY(), this.getZ() + oz,
                        1, 0, 0, 0, 0.02);
            }

            this.remoteControlTicks++;
            if (this.remoteControlTicks < 10) {
                // 本体の向き（ヨー・ピッチ）を滑らかに同期
                this.setYRot(this.getYRot() + (this.targetYRot - this.getYRot()) * 0.5f);
                this.yBodyRot = this.getYRot();
                this.yHeadRot = this.getYRot();
                this.setXRot(this.getXRot() + (this.targetXRot - this.getXRot()) * 0.5f);

                // ロール（傾き）の同期（物理演算とは逆に傾けることで自然な旋回に見せる）
                this.setRoll(-this.targetRoll);

                // Shared physics constants (must match client-side prediction)
                double hMaxSpeed = 50.0;
                double vMaxSpeed = 17.5;
                double hAcceleration = 0.15;
                double vAcceleration = 0.25;
                double hFriction = 0.998;
                double vFriction = 0.98;

                net.minecraft.world.phys.Vec3 currentVel = this.getDeltaMovement();
                double vx = currentVel.x, vy = currentVel.y, vz = currentVel.z;

                if (!this.remotePowerOff) {
                    if (this.remoteFlightMode == 0) {
                        // LANDED State - Stop sideways movement entirely, rely on ground friction and
                        // gravity
                        this.setNoGravity(false);
                        vx *= hFriction;
                        vz *= hFriction;
                    } else if (this.remoteFlightMode == 1) {
                        // 離陸状態：垂直方向への跳躍を許可し、水平方向は減衰
                        this.setNoGravity(false);
                        vx *= hFriction;
                        vz *= hFriction;
                    } else {
                        // 飛行状態
                        this.setNoGravity(true);
                        float pitchRad = (float) Math.toRadians(this.getXRot());
                        float yawRad = (float) Math.toRadians(this.getYRot());
                        float rollRad = (float) Math.toRadians(this.targetRoll);

                        double hoverThrust = vAcceleration - 0.02; // 重力減衰の補正
                        double ax = 0, ay = 0, az = 0;

                        if (this.remoteCameraMode == 3) {
                            // プレイヤー視点モード (Mode 3)：直感的なWASD移動
                            double speed = hAcceleration * 0.8;
                            if (this.remoteBoost)
                                speed *= 2.0;
                            ax = -Math.sin(yawRad) * this.remoteForward * speed
                                    - Math.cos(yawRad) * this.remoteSideways * speed;
                            az = Math.cos(yawRad) * this.remoteForward * speed
                                    - Math.sin(yawRad) * this.remoteSideways * speed;

                            // 上下移動はジャンプ/スニークで直接制御
                            if (this.remoteUp && !this.onGround())
                                ay += 0.05;
                            if (this.remoteSneaking && !this.onGround())
                                ay -= 0.05;
                        } else {
                            // 推力ベクトル物理モード (Mode 0, 1, 2)
                            double liftMod = 0;

                            // 前進・後退時の揚力/沈み込み補正
                            if (this.remoteForward > 0 && !this.onGround()) {
                                liftMod = -0.005 * this.getXRot();
                            } else if (this.remoteForward < 0 && !this.onGround()) {
                                liftMod = 0.005 * this.getXRot();
                            }

                            // ジャンプ・スニークによる各動作時の垂直補正
                            if (this.remoteUp && !this.onGround())
                                liftMod += 0.05;
                            if (this.remoteSneaking && !this.onGround())
                                liftMod -= 0.05;

                            double finalThrust = hoverThrust + liftMod;

                            // 実際の3Dベクトル推力計算
                            org.joml.Vector3f upVec = new org.joml.Vector3f(0f, 1f, 0f);
                            org.joml.Vector3f forwardVec = new org.joml.Vector3f(0f, 0f, 1f);
                            org.joml.Quaternionf q = new org.joml.Quaternionf()
                                    .rotationY(-yawRad)
                                    .rotateX(pitchRad)
                                    .rotateZ(rollRad);
                            upVec.rotate(q);
                            forwardVec.rotate(q);

                            // 機体の上方向ベクトルへ推力を適用
                            ax = (double) upVec.x * finalThrust;
                            ay = (double) upVec.y * finalThrust - hoverThrust;
                            az = (double) upVec.z * finalThrust;

                            // 前方ブーストの適用
                            if (this.remoteBoost) {
                                double boostPower = 0.2;
                                ax += (double) forwardVec.x * boostPower;
                                ay += (double) forwardVec.y * boostPower;
                                az += (double) forwardVec.z * boostPower;
                            }
                        }

                        vx = (vx + ax) * hFriction;
                        vy = (vy + ay) * vFriction;
                        vz = (vz + az) * hFriction;
                    }
                } else {
                    this.setNoGravity(false);
                    vx *= hFriction;
                    vz *= hFriction;
                }

                double horizontalSpeedSq = vx * vx + vz * vz;
                if (horizontalSpeedSq > hMaxSpeed * hMaxSpeed) {
                    double scale = hMaxSpeed / Math.sqrt(horizontalSpeedSq);
                    vx *= scale;
                    vz *= scale;
                }
                if (vy > vMaxSpeed)
                    vy = vMaxSpeed;
                if (vy < -vMaxSpeed)
                    vy = -vMaxSpeed;

                this.setDeltaMovement(vx, vy, vz);
            } else {
                this.setNoGravity(false);
                if (this.currentOperator != null) {
                    this.currentOperator = null;
                }
            }
        }
    }

    /**
     * リモート操作入力をサーバー側エンティティに反映させる。
     */
    public void setRemoteInput(float forward, float sideways, boolean up, boolean sneaking, float yRot, float xRot,
            float roll, boolean powerOff, int flightMode, int cameraMode, boolean boost, Player sp) {
        this.remoteForward = forward;
        this.remoteSideways = sideways;
        this.remoteUp = up;
        this.remoteSneaking = sneaking;
        this.targetYRot = yRot;
        this.targetXRot = xRot;
        this.targetRoll = roll;
        this.remotePowerOff = powerOff;
        this.setPowerOff(powerOff);
        this.remoteFlightMode = flightMode;
        this.setFlightMode(flightMode);
        this.remoteCameraMode = cameraMode;
        this.remoteBoost = boost;
        this.currentOperator = sp;
        this.remoteControlTicks = 0; // タイムアウトをリセット
    }

    public void triggerJump(float power) {
        if (this.onGround()) {
            this.setDeltaMovement(this.getDeltaMovement().add(0, power, 0));
        }
    }

    @Override
    public void remove(Entity.RemovalReason reason) {
        // エンティティ削除時に強制ロードチャンクを解除
        if (!this.level().isClientSide() && lastForcedChunk != null) {
            ((ServerLevel) this.level()).setChunkForced(lastForcedChunk.x, lastForcedChunk.z, false);
            lastForcedChunk = null;
        }
        super.remove(reason);
    }

    @Override
    public InteractionResult mobInteract(Player player, InteractionHand hand) {
        ItemStack itemInHand = player.getItemInHand(hand);

        // コントローラーを持っている場合：ドローンとのペアリング（周波数同期）
        if (itemInHand.is(ItemRegistry.CONTROLLER)) {
            if (!this.level().isClientSide()) {
                if (this.getFrequency() == 0) {
                    this.setFrequency(this.random.nextInt(999998) + 1);
                }

                CustomData cd = itemInHand.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY);
                CompoundTag tag = cd.copyTag();
                tag.putInt("DroneFrequency", this.getFrequency());
                itemInHand.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));

                player.displayClientMessage(
                        Component.translatable("message.fpv_drone.linked", Component.literal("Freq: " + this.getFrequency())),
                        true);
            }
            return InteractionResult.SUCCESS;
        }

        // 素手などの場合：ドローンをアイテムとして回収
        if (!this.level().isClientSide()) {
            ItemStack droneItem = new ItemStack(ItemRegistry.DRONE);
            if (this.getFrequency() != 0) {
                CompoundTag tag = new CompoundTag();
                tag.putInt("DroneFrequency", this.getFrequency());
                droneItem.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
            }
            this.spawnAtLocation((ServerLevel) this.level(), droneItem);
            this.discard();
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    public void die(net.minecraft.world.damagesource.DamageSource cause) {
        if (!this.level().isClientSide() && this.getFrequency() > 0) {
            me.sisui.fpv_drone.network.DroneDeathPayload payload = new me.sisui.fpv_drone.network.DroneDeathPayload(
                    this.getFrequency());
            for (net.minecraft.server.level.ServerPlayer p : ((net.minecraft.server.level.ServerLevel) this.level())
                    .players()) {
                net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(p, payload);
            }
        }
        super.die(cause);
    }

    // --- エンティティ同士の衝突でドローンが押し出されるのを防ぐ設定 ---
    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    public void push(Entity entity) {
        // 何もしない
    }

    @Override
    protected void doPush(Entity entity) {
        // 何もしない
    }
}
