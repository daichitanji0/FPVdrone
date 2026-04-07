package me.sisui.fpv_drone;

import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import software.bernie.geckolib.renderer.GeoEntityRenderer;
import software.bernie.geckolib.renderer.base.GeoRenderState;
import software.bernie.geckolib.renderer.base.RenderPassInfo;
import org.joml.Quaternionf;

public class DroneEntityRenderer<R extends LivingEntityRenderState & GeoRenderState>
        extends GeoEntityRenderer<DroneEntity, R> {
    
    private float currentRoll = 0f;
    private float currentPitch = 0f;
    
    public DroneEntityRenderer(EntityRendererProvider.Context context) {
        super(context, new DroneEntityModel());
    }

    @Override
    public void captureDefaultRenderState(DroneEntity animatable, Void relatedObject, R renderState, float partialTick) {
        super.captureDefaultRenderState(animatable, relatedObject, renderState, partialTick);
        // If this is the drone currently controlled by the client
        if (OperatingState.isOperatingDrone && OperatingState.activeDrone == animatable) {
            if (OperatingState.state == OperatingState.DroneState.LANDED) {
                this.currentRoll = 0f;
                this.currentPitch = 0f;
            } else {
                this.currentRoll = -net.minecraft.util.Mth.lerp(partialTick, OperatingState.prevRoll, OperatingState.roll);
                this.currentPitch = net.minecraft.util.Mth.lerp(partialTick, animatable.xRotO, animatable.getXRot());
            }
        } else {
            // Otherwise, fall back to the synced EntityData roll
            this.currentRoll = animatable.getRoll();
            this.currentPitch = net.minecraft.util.Mth.lerp(partialTick, animatable.xRotO, animatable.getXRot());
        }
    }

    @Override
    public void adjustRenderPose(RenderPassInfo<R> renderPassInfo) {
        super.adjustRenderPose(renderPassInfo);
        
        // Apply the pitch rotation to the PoseStack so the whole model tilts up and down
        if (currentPitch != 0f) {
            float pitchRad = (float)Math.toRadians(-currentPitch * 0.7f);
            renderPassInfo.poseStack().mulPose(new Quaternionf().rotationX(pitchRad));
        }
        
        // Apply the roll rotation to the PoseStack so the whole model tilts
        if (currentRoll != 0f) {
            float rollRad = (float)Math.toRadians(currentRoll);
            // Rotate around Z-axis using JOML Quaternion
            renderPassInfo.poseStack().mulPose(new Quaternionf().rotationZ(rollRad));
        }
    }
}
