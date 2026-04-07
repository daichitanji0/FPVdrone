package me.sisui.fpv_drone.mixin.client;

import me.sisui.fpv_drone.OperatingState;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.client.renderer.culling.Frustum;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * ドローン操作中、ドローンのカメラが遠くにあっても、ローカルプレイヤーが常にカリングされずに描画されるようにするためのミックスイン。
 */
@Mixin(net.minecraft.client.renderer.entity.EntityRenderDispatcher.class)
public class PlayerRenderMixin {

    @Inject(method = "shouldRender", at = @At("HEAD"), cancellable = true)
    private <E extends Entity> void fpv_drone$forceRenderWhileOperating(E entity, Frustum frustum, double x, double y, double z, CallbackInfoReturnable<Boolean> cir) {
        if (OperatingState.isOperatingDrone && OperatingState.cameraMode != 3) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null && entity == mc.player) {
                cir.setReturnValue(true);
            }
        }
    }
}
