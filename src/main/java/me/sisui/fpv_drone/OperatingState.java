package me.sisui.fpv_drone;

/**
 * クライアント側でのドローン操作状態を一元管理するクラス。
 * 操縦フラグ、周波数、各種入力、カメラ状態などを保持します。
 */
public class OperatingState {
    public static boolean isOperatingDrone = false;
    public static int operatingFrequency = 0;

    // カメラモード: 0 = ドローン一人称, 1 = ドローン三人称(背面), 2 = ドローン三人称(前面), 3 = プレイヤー視点
    public static int cameraMode = 0;
    public static boolean powerOff = false;

    // ロールベースのフライトモデル（クライアント専用）
    public static float roll = 0; // 現在のロール角 (度, -60..+60)
    public static float prevRoll = 0; // 補間用の前ティックのロール角
    public static float targetRoll = 0; // 目標ロール角 (度, -60..+60)
    public static float droneYaw = 0; // 累積ヨー角
    
    // 仮想ジョイスティックの状態
    public static float joyX = 0; 
    public static float joyY = 0;
    public static float targetPitch = 0; // joyYから算出される目標ピッチ角
    public static float jumpPower = 0f;
    public static int jumpChargeTicks = 0;
    public static boolean isJumpD = false; // 即時のスロットル状態

    public enum DroneState {
        LANDED,
        TAKING_OFF,
        FLIGHT
    }
    public static DroneState state = DroneState.LANDED;
    public static boolean wasOnGround = true; // 接地状態
    public static boolean prevJumpD = false;
    public static long lastJumpPressTime = 0;
    
    // マウス移動の追跡
    public static double lastMouseX = 0;
    public static double lastMouseY = 0;
    public static boolean initializedMouse = false;
    
    // キーボード入力のスムージング処理
    public static float smoothedLeftInput = 0f;
    public static float smoothedRightInput = 0f;

    // 通信ロスト・追跡不能時間のトラッキング
    public static int lostDroneTicks = 0;

    public static DroneEntity activeDrone = null; // 現在レンダリングされているアクティブなドローンの参照
    
    // ブースト機能
    public static float boostEnergy = 1.0f; // 0.0 ～ 1.0 のエネルギー量
    public static boolean isBoosting = false;

    // ドローン破壊時のカメラ遷移遅延機能
    public static int deathCameraTicks = 0;
    public static net.minecraft.world.phys.Vec3 deathCameraPos = null;
    public static float deathCameraXRot = 0f;
    public static float deathCameraYRot = 0f;

    // サーバーへの状態同期フラグ
    public static boolean sentDroneStatePacket = false;
    
    // 音量減衰計算のためのプレイヤーの元座標
    public static net.minecraft.world.phys.Vec3 playerOrigin = null;
    
    // 振動エフェクトのためのキー入力エッジ検出用
    public static boolean prevUpD = false;
    public static boolean prevDownD = false;
    public static boolean prevLeftD = false;
    public static boolean prevRightD = false;
    public static boolean prevJumpKeyD = false;
    public static boolean prevSneakD = false;
    public static boolean prevAttackD = false;

    // 通信ロスト時（ノイズ）のアニメーション
    public static boolean isDeathAnimating = false;
    public static float deathAnimFrame = 0f;
    public static final float MAX_DEATH_FRAMES = 24f;
    public static final float DEATH_ANIM_TICK_SPEED = 24f / 20f; // 1秒間に24フレーム (20 ticks)
}
