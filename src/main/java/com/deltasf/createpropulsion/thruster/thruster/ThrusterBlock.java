package com.deltasf.createpropulsion.thruster.thruster;

import com.deltasf.createpropulsion.registries.PropulsionBlockEntities;
import com.deltasf.createpropulsion.thruster.AbstractThrusterBlock;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntityTicker;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class ThrusterBlock extends AbstractThrusterBlock {
    /**
     * Flipped to true by {@link ThrusterBlockEntity#formMulti} on every cell
     * of a cube the moment it assembles, and back to false by
     * {@link ThrusterBlockEntity#disassembleMulti} when it dissolves. The
     * blockstate picks this up and swaps the cell's model to an empty one so
     * the "bunch of propellers" visual disappears inside a formed multi --
     * the controller's BER then draws the single purpose-made multiblock
     * model in their place. Does not affect ticking, BE identity, or fluid
     * routing; purely cosmetic.
     */
    public static final BooleanProperty MULTIBLOCK = BooleanProperty.create("multi");

    public ThrusterBlock(Properties properties) {
        super(properties);
        this.registerDefaultState(this.defaultBlockState().setValue(MULTIBLOCK, false));
    }

    @Override
    protected void createBlockStateDefinition(@Nonnull StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(MULTIBLOCK);
    }

    @Override
    public BlockEntity newBlockEntity(@Nonnull BlockPos pos, @Nonnull BlockState state) {
        return new ThrusterBlockEntity(PropulsionBlockEntities.THRUSTER_BLOCK_ENTITY.get(), pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(@Nonnull Level level, @Nonnull BlockState state, @Nonnull BlockEntityType<T> type) {
        if (type == PropulsionBlockEntities.THRUSTER_BLOCK_ENTITY.get()) {
            return new SmartBlockEntityTicker<>();
        }
        return null;
    }
}