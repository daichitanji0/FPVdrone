package me.sisui.fpv_drone;

import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import java.lang.Math;

public class DroneWindSound extends AbstractTickableSoundInstance {

    private final DroneEntity drone;
    private float throttleState = 0.0f; // 0.0 to 1.0 accumulated boost

    public DroneWindSound(DroneEntity drone) {
        super(FPVDroneMod.DRONE_MOTOR_EVENT, SoundSource.NEUTRAL, RandomSource.create());
        this.drone = drone;
        this.looping = true;
        this.delay = 0;
        this.volume = 0.0f;
        this.pitch = 1.0f;
    }

    @Override
    public void tick() {
        if (drone == null || !drone.isAlive()) {
            this.stop();
            return;
        }

        // --- Distance-based attenuation ---
        boolean isPiloting = me.sisui.fpv_drone.OperatingState.isOperatingDrone;
        
        float distanceFalloff = 1.0f;
        if (isPiloting && me.sisui.fpv_drone.OperatingState.playerOrigin != null) {
            double dist = Math.sqrt(drone.distanceToSqr(me.sisui.fpv_drone.OperatingState.playerOrigin));
            distanceFalloff = (float) Math.max(0.0, 1.0 - (dist / 50.0)); // Silent at 50 blocks
        } else if (!isPiloting && me.sisui.fpv_drone.OperatingState.cameraMode == 3) {
            // In Mode 3 (player viewpoint), use vanilla distance from player camera
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            if (mc.player != null) {
                double dist = Math.sqrt(mc.player.distanceToSqr(drone));
                distanceFalloff = (float) Math.max(0.0, 1.0 - (dist / 50.0));
            }
        }

        if (drone.isPowerOff()) {
            // Engine off - rapid fade
            this.volume *= 0.8f;
            this.pitch += (0.6f - this.pitch) * 0.3f; // Wind down
            drone.propellerSpeedMul = Math.max(0.0f, drone.propellerSpeedMul - 0.05f);
        } else if (drone.getFlightMode() == 0) {
            // --- LANDED / IDLE mode ---
            float targetVol = 0.25f * distanceFalloff;
            float targetPitch = 0.8f; // Low idle hum

            // Jump charge ramps up pitch and volume
            float jumpCharge = me.sisui.fpv_drone.OperatingState.jumpPower;
            if (isPiloting && jumpCharge > 0) {
                targetPitch += jumpCharge * 1.5f; // 0.8 -> up to 2.3
                targetVol += jumpCharge * 0.4f;
            }

            this.volume += (Math.min(0.8f, targetVol) - this.volume) * 0.15f;
            this.pitch += (targetPitch - this.pitch) * 0.15f;
            drone.propellerSpeedMul += (Math.max(0.6f, 0.6f + jumpCharge * 1.2f) - drone.propellerSpeedMul) * 0.15f;
        } else {
            // --- FLIGHT mode ---
            // Tilt intensity from actual entity rotation
            float tiltIntensity = (Math.abs(drone.getXRot()) + Math.abs(drone.getRoll())) / 60.0f;

            // Pilot input intensity (instant response)
            float pilotMod = 0.0f;
            if (isPiloting) {
                pilotMod = (Math.abs(me.sisui.fpv_drone.OperatingState.targetPitch) 
                          + Math.abs(me.sisui.fpv_drone.OperatingState.targetRoll)) / 60.0f;
            }

            // Ascend load (climbing = high pitch)
            float ascendLoad = (float) Math.max(0.0, drone.getDeltaMovement().y * 2.5);

            // Speed factor
            double speed = drone.getDeltaMovement().length();
            float speedIntensity = (float) Math.min(speed / 1.5, 1.0);

            // throttle contribution (holding jump key) - ACCUMULATES gradually
            if (isPiloting && me.sisui.fpv_drone.OperatingState.isJumpD) {
                throttleState = Math.min(1.0f, throttleState + 0.04f); // ~1.25s to full pitch
            } else {
                throttleState = Math.max(0.0f, throttleState - 0.08f); // Faster wind-down
            }
            float throttleMod = throttleState * 1.8f; 

            // Combined intensity: GENTLE tilt weighting (0.8 instead of 3.0)
            float totalIntensity = Math.max(tiltIntensity, pilotMod) * 0.8f + throttleMod + ascendLoad + speedIntensity * 0.3f;

            // Volume is stable
            float targetVol = (0.35f + totalIntensity * 0.05f) * distanceFalloff;
            float targetPitch = 1.0f + totalIntensity * 1.5f;

            // Lerp with responsive rate
            this.volume += (Math.min(0.8f, targetVol) - this.volume) * 0.3f;
            this.pitch += (Math.min(4.5f, targetPitch) - this.pitch) * 0.3f;
            
            // Propeller visual speed follows pitch - increased overall speed
            drone.propellerSpeedMul += (Math.min(5.0f, 1.2f + totalIntensity * 1.5f) - drone.propellerSpeedMul) * 0.4f;
        }

        this.x = (double) this.drone.getX();
        this.y = (double) this.drone.getY();
        this.z = (double) this.drone.getZ();
    }

    @Override
    public boolean canStartSilent() {
        return true;
    }

    public void stopSound() {
        this.stop();
    }
}
