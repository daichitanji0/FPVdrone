package me.sisui.fpv_drone;

import net.minecraft.resources.Identifier;
import software.bernie.geckolib.model.GeoModel;
import software.bernie.geckolib.renderer.base.GeoRenderState;

public class DroneEntityModel extends GeoModel<DroneEntity> {

    @Override
    public Identifier getModelResource(GeoRenderState renderState) {
        return Identifier.fromNamespaceAndPath(FPVDroneMod.MOD_ID, "entity/drone");
    }

    @Override
    public Identifier getTextureResource(GeoRenderState renderState) {
        return Identifier.fromNamespaceAndPath(FPVDroneMod.MOD_ID, "textures/entity/drone.png");
    }

    @Override
    public Identifier getAnimationResource(DroneEntity animatable) {
        return Identifier.fromNamespaceAndPath(FPVDroneMod.MOD_ID, "entity/drone");
    }
}
