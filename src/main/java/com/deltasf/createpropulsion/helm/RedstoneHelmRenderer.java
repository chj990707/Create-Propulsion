package com.deltasf.createpropulsion.helm;

import com.deltasf.createpropulsion.registries.PropulsionPartialModels;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import com.simibubi.create.foundation.blockEntity.renderer.SafeBlockEntityRenderer;

import net.createmod.catnip.render.CachedBuffers;
import net.createmod.catnip.render.SuperByteBuffer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Renders the redstone helm's built-in wheel, interpolating the rotation
 * between client ticks for a smooth animation.
 */
public class RedstoneHelmRenderer extends SafeBlockEntityRenderer<RedstoneHelmBlockEntity> {
    //Wheel hub in the partial model, in block coordinates (8, 13, 12 in pixels)
    private static final double PIVOT_X = 0.5;
    private static final double PIVOT_Y = 0.8125;
    private static final double PIVOT_Z = 0.75;

    public RedstoneHelmRenderer(BlockEntityRendererProvider.Context context) { super(); }

    @Override
    protected void renderSafe(RedstoneHelmBlockEntity helm, float partialTicks, PoseStack poseStack,
                              MultiBufferSource bufferSource, int light, int overlay) {
        //Interpolate between the last two client ticks for smooth wheel motion
        float angle = Mth.lerp(partialTicks, helm.prevVisualAngle, helm.visualAngle) % 360.0f;
        Direction facing = helm.getBlockState().getValue(RedstoneHelmBlock.FACING);

        BlockState state = helm.getBlockState();
        SuperByteBuffer wheel = CachedBuffers.partial(PropulsionPartialModels.REDSTONE_HELM_WHEEL, state);
        VertexConsumer buffer = bufferSource.getBuffer(RenderType.cutout());

        poseStack.pushPose();

        //Orient the wheel with the helm. The partial is authored for a north-facing helm.
        poseStack.translate(0.5, 0.5, 0.5);
        poseStack.mulPose(Axis.YP.rotationDegrees(horizontalAngle(facing)));
        poseStack.translate(-0.5, -0.5, -0.5);

        //Spin the wheel around its hub
        poseStack.translate(PIVOT_X, PIVOT_Y, PIVOT_Z);
        poseStack.mulPose(Axis.ZP.rotationDegrees(angle));
        poseStack.translate(-PIVOT_X, -PIVOT_Y, -PIVOT_Z);

        wheel.light(light).renderInto(poseStack, buffer);

        poseStack.popPose();
    }

    private static float horizontalAngle(Direction facing) {
        switch (facing) {
            case EAST: return 270;
            case SOUTH: return 180;
            case WEST: return 90;
            case NORTH:
            default: return 0;
        }
    }
}
