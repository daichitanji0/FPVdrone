package me.sisui.fpv_drone.mixin.client;

import me.sisui.fpv_drone.DroneEntity;
import me.sisui.fpv_drone.OperatingState;
import net.minecraft.client.Camera;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * ドローン視点（モード0〜2）の時にカメラにロール（傾き）を適用するためのミックスイン。
 */
@Mixin(Camera.class)
public class CameraRollMixin {

    @Shadow @Final private Quaternionf rotation;
    @Shadow @Final private Vector3f forwards;
    @Shadow @Final private Vector3f up;
    @Shadow @Final private Vector3f left;

    @Inject(method = "setup", at = @At("RETURN"))
    private void fpv_drone$applyRoll(net.minecraft.world.level.Level level, Entity entity, boolean isThirdPerson,
                                      boolean isMirrored, float partialTick, CallbackInfo ci) {
        if (OperatingState.isOperatingDrone && OperatingState.cameraMode != 3
                && entity instanceof DroneEntity) {
            float lerpedRoll = net.minecraft.util.Mth.lerp(partialTick, OperatingState.prevRoll, OperatingState.roll);
            float rollRad = (float) Math.toRadians(-lerpedRoll);
            rotation.rotateZ(rollRad);
            forwards.set(0, 0, 1).rotate(rotation);
            up.set(0, 1, 0).rotate(rotation);
            left.set(1, 0, 0).rotate(rotation);
        }
    }
}
