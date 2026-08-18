package com.deltasf.createpropulsion.thruster.ion;

import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.foundation.blockEntity.renderer.SmartBlockEntityRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;

/**
 * Renderer for the Ion Thruster.
 *
 * <p>Deliberately a no-op in the single-block case, mirroring
 * {@link com.deltasf.createpropulsion.thruster.thruster.ThrusterRenderer}'s
 * single-block path (the blockstate JSON handles every facing; a BER
 * contribution is only needed for the multiblock model that is authored in
 * single-block coords and scaled at draw time). The fluid thruster's
 * {@code ThrusterRenderer} can't be reused directly because it is generic
 * over {@link com.deltasf.createpropulsion.thruster.thruster.ThrusterBlockEntity}
 * and calls multiblock-only hooks ({@code isController}, {@code isMultiblock},
 * {@code getWidth}) that do not exist on {@link IonThrusterBlockEntity};
 * this class takes the same structural position for the ion variant.
 *
 * <p>When multiblock Ion Thrusters are added later, the multiblock draw path
 * from {@code ThrusterRenderer} (facing rotation, anchor-at-cube-center,
 * uniform scale by width) should be factored out into a shared helper and
 * invoked from both renderers. Until then, single-block rendering is
 * complete without any BER work.
 */
public class IonThrusterRenderer extends SmartBlockEntityRenderer<IonThrusterBlockEntity> {

    public IonThrusterRenderer(BlockEntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    protected void renderSafe(IonThrusterBlockEntity be, float partialTicks, PoseStack ms,
                              MultiBufferSource buffer, int light, int overlay) {
        // Single-block only: chunk mesher draws the JSON model. Nothing to
        // contribute from a BER. Left as an overridable entry point so a
        // future multiblock path can slot in without changing the
        // registration in PropulsionBlockEntities.
    }
}