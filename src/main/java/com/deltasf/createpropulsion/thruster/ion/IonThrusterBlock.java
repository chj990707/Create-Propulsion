package com.deltasf.createpropulsion.thruster.ion;

import com.deltasf.createpropulsion.registries.PropulsionBlockEntities;
import com.deltasf.createpropulsion.thruster.AbstractThrusterBlock;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntityTicker;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Ion Thruster block. Structurally mirrors
 * {@link com.deltasf.createpropulsion.thruster.thruster.ThrusterBlock} but
 * omits the {@code MULTIBLOCK} blockstate property: this initial iteration
 * supports single-block ion thrusters only. When multiblock support is
 * added later, this class is the place where the cosmetic model-swap
 * property would be re-introduced alongside the corresponding logic in
 * {@link IonThrusterBlockEntity}.
 */
public class IonThrusterBlock extends AbstractThrusterBlock {

    public IonThrusterBlock(Properties properties) {
        super(properties);
    }

    @Override
    public BlockEntity newBlockEntity(@Nonnull BlockPos pos, @Nonnull BlockState state) {
        return new IonThrusterBlockEntity(
                PropulsionBlockEntities.ION_THRUSTER_BLOCK_ENTITY.get(), pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(@Nonnull Level level,
                                                                  @Nonnull BlockState state,
                                                                  @Nonnull BlockEntityType<T> type) {
        if (type == PropulsionBlockEntities.ION_THRUSTER_BLOCK_ENTITY.get()) {
            return new SmartBlockEntityTicker<>();
        }
        return null;
    }
}