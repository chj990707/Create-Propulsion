package com.deltasf.createpropulsion.thruster.thruster;

import com.deltasf.createpropulsion.registries.PropulsionPartialModels;
import com.deltasf.createpropulsion.thruster.AbstractThrusterBlock;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import com.simibubi.create.foundation.blockEntity.renderer.SmartBlockEntityRenderer;

import dev.engine_room.flywheel.lib.model.baked.PartialModel;
import net.createmod.catnip.render.CachedBuffers;
import net.createmod.catnip.render.SuperByteBuffer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Renderer for the fuel thruster. Draws nothing on the controller in single
 * mode (the single-block JSON model handles that via the blockstate), draws
 * nothing on slaves (their MULTIBLOCK=true blockstate swaps them to the
 * empty model), and draws the purpose-made multiblock model on a controller
 * that is part of a formed cube.
 *
 * <p>The multiblock partial model (see PropulsionPartialModels and
 * assets/.../partial/thruster_multiblock_*.json) is authored in the normal
 * [0,16] block-model coordinate space, as if it were for a 1x1x1 block.
 * This renderer scales the whole drawn geometry by the cube width at draw
 * time so the same authoring space stretches to cover the full 2x2x2 or
 * 3x3x3 footprint. Modders who want to supply their own model should keep
 * their authoring coords in [0,16] -- do NOT author at [0,32] / [0,48].
 *
 * <p>The BE's {@link ThrusterBlockEntity#getRenderBoundingBox} grows the
 * chunk-culling AABB to match the cube, so the oversized model does not get
 * clipped when the camera looks at the cube from the side.
 */
public class ThrusterRenderer extends SmartBlockEntityRenderer<ThrusterBlockEntity> {

    public ThrusterRenderer(BlockEntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    protected void renderSafe(ThrusterBlockEntity be, float partialTicks, PoseStack ms,
                              MultiBufferSource buffer, int light, int overlay) {
        // Note: we intentionally do NOT short-circuit on
        // VisualizationManager.supportsVisualization(). Unlike the creative
        // thruster (which registers a Flywheel Visual alongside its
        // Renderer), this thruster has no multiblock Visual -- a Flywheel
        // early-return here would hide the model entirely whenever Flywheel
        // is active (which is the default with Create + VS2). If a Visual
        // is added later this check can be re-introduced.

        // Slaves: the cube's shared model is drawn from the controller. A
        // slave's own cell is already empty via the MULTIBLOCK=true
        // blockstate, so there is nothing to draw here.
        if (!be.isController()) return;

        // Single-block controller: the JSON blockstate model is drawn by the
        // regular chunk mesher. No BER contribution needed.
        if (!be.isMultiblock()) return;

        PartialModel model = getMultiblockModel(be.getWidth());
        if (model == null) return;

        BlockState state = be.getBlockState();
        Direction facing = state.getValue(AbstractThrusterBlock.FACING);
        int w = be.getWidth();

        SuperByteBuffer mb = CachedBuffers.partial(model, state);
        VertexConsumer vb = buffer.getBuffer(RenderType.cutoutMipped());

        ms.pushPose();

        // Anchor at the cube center so subsequent rotations and the final
        // un-translate symmetrise around the middle of the cube (width/2),
        // not the controller cell (0.5). Without this, y=180/x=90 etc.
        // would rotate the cube model through neighbouring cells and it
        // would end up inverted on the wrong side of the multi.
        float cx = w * 0.5f;
        ms.translate(cx, cx, cx);
        applyFacingRotation(ms, facing);
        ms.translate(-cx, -cx, -cx);

        // Scale uniformly by cube width. The model is authored in a [0,16]
        // single-block space, so scaling by w stretches it to fill [0, w*16]
        // in model units, i.e. w blocks in world units. Do this AFTER the
        // facing rotation so the rotation uses unscaled coordinates and
        // matches the single-block model's conventions exactly.
        ms.scale(w, w, w);

        mb.light(light).overlay(overlay).renderInto(ms, vb);

        ms.popPose();
    }

    /**
     * Returns the authored multiblock model for the given cube width, or
     * null if the width has no registered model (which includes width==1
     * and any future widths > 3). A null return short-circuits rendering
     * without crashing on an unknown size.
     */
    private static PartialModel getMultiblockModel(int width) {
        if (width == 2) return PropulsionPartialModels.THRUSTER_MULTIBLOCK_2X2X2;
        if (width == 3) return PropulsionPartialModels.THRUSTER_MULTIBLOCK_3X3X3;
        return null;
    }

    /**
     * Apply the rotation the vanilla blockstate JSON applies for the given
     * facing, so the authored multiblock model lines up with facing=north
     * exactly the same way the single-block model does. Keeping the axes
     * and angles identical to the JSON means a modder authoring a
     * multiblock model only needs to match the single-block model's
     * orientation to get all six facings right.
     *
     * <p>Sign convention: Minecraft's blockstate JSON "y"/"x" rotations are
     * applied via {@code BlockModelRotation}, which builds its quaternion
     * from the NEGATED angles ({@code rotateYXZ(-yRot, -xRot, 0)}). That is,
     * JSON {@code "y": 90} rotates the model in the opposite sense from a
     * naive {@code Axis.YP.rotationDegrees(+90)}. Using the positive angles
     * here would therefore render the multiblock model 180 degrees off for
     * any facing where the rotation isn't self-inverse -- visibly, the
     * cube's front and back ended up swapped on EAST/WEST (and
     * symmetrically on UP/DOWN). NORTH (identity) and SOUTH (180 degrees,
     * self-inverse) happen to render the same either way.
     *
     * <p>We negate here so the BER's rotations match JSON semantics. The
     * single-block model (authored with its nozzle protruding toward +Z)
     * and the multiblock model (authored the same way) then orient
     * consistently across all six facings.
     */
    private static void applyFacingRotation(PoseStack ms, Direction facing) {
        switch (facing) {
            case NORTH -> { /* identity */ }
            case SOUTH -> ms.mulPose(Axis.YP.rotationDegrees(-180));
            case WEST  -> ms.mulPose(Axis.YP.rotationDegrees(-270));
            case EAST  -> ms.mulPose(Axis.YP.rotationDegrees(-90));
            case UP    -> ms.mulPose(Axis.XP.rotationDegrees(-270));
            case DOWN  -> ms.mulPose(Axis.XP.rotationDegrees(-90));
        }
    }
}