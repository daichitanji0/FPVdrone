package me.sisui.fpv_drone.mixin.client;

import me.sisui.fpv_drone.OperatingState;
import net.minecraft.client.renderer.ItemInHandRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * ドローンの操縦中にプレイヤーの手や持っているアイテムが描画されないようにするためのミックスイン。
 */
@Mixin(ItemInHandRenderer.class)
public class HandRenderMixin {
    @Inject(method = "renderHandsWithItems", at = @At("HEAD"), cancellable = true)
    private void fpv_drone$hideHand(float f, com.mojang.blaze3d.vertex.PoseStack poseStack, net.minecraft.client.renderer.SubmitNodeCollector submitNodeCollector, net.minecraft.client.player.LocalPlayer localPlayer, int i, CallbackInfo ci) {
        if (OperatingState.isOperatingDrone) {
            ci.cancel();
        }
    }
}
