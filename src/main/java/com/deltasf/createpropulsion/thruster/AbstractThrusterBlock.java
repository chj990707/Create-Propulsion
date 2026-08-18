package com.deltasf.createpropulsion.thruster;

import com.deltasf.createpropulsion.registries.PropulsionShapes;
import com.deltasf.createpropulsion.thruster.thruster.ThrusterBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DirectionalBlock;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

//TODO: IBE
@SuppressWarnings("deprecation")
public abstract class AbstractThrusterBlock extends DirectionalBlock implements EntityBlock {
    public static final DirectionProperty FACING = BlockStateProperties.FACING;

    protected AbstractThrusterBlock(Properties properties) {
        super(properties);
        this.registerDefaultState(this.stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override
    public VoxelShape getShape(@Nullable BlockState pState, @Nullable BlockGetter pLevel, @Nullable BlockPos pPos, @Nullable CollisionContext pContext) {
        if (pState == null) {
            return PropulsionShapes.THRUSTER.get(Direction.NORTH);
        }
        Direction direction = pState.getValue(FACING);
        if (direction == Direction.UP || direction == Direction.DOWN) direction = direction.getOpposite();
        return PropulsionShapes.THRUSTER.get(direction);
    }

    @Override
    public BlockState getStateForPlacement(@Nonnull BlockPlaceContext context) {
        Direction baseDirection = context.getNearestLookingDirection();
        Direction placeDirection;
        Player player = context.getPlayer();
        if (player != null) {
            placeDirection = !player.isShiftKeyDown() ? baseDirection : baseDirection.getOpposite();
        } else {
            placeDirection = baseDirection.getOpposite();
        }

        return this.defaultBlockState().setValue(FACING, placeDirection);
    }

    @Override
    protected void createBlockStateDefinition(@Nonnull StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Nullable
    @Override
    public abstract BlockEntity newBlockEntity(@Nonnull BlockPos pos, @Nonnull BlockState state);

    @Nullable
    @Override
    public abstract <T extends BlockEntity> BlockEntityTicker<T> getTicker(@Nonnull Level level, @Nonnull BlockState state, @Nonnull BlockEntityType<T> type);

    @Override
    public void onPlace(@Nonnull BlockState state, @Nonnull Level level, @Nonnull BlockPos pos, @Nonnull BlockState oldState, boolean isMoving) {
        super.onPlace(state, level, pos, oldState, isMoving);

        if (!level.isClientSide()) {
            doRedstoneCheck(level, state, pos);
        }
    }

    @Override
    public void onRemove(@Nonnull BlockState state, @Nonnull Level level, @Nonnull BlockPos pos, @Nonnull BlockState newState, boolean isMoving) {
        if (!state.is(newState.getBlock())) {
            if (!level.isClientSide()) {
                // If this thruster was part of a multiblock, dissolve the
                // whole multi BEFORE the BE is torn down. disassembleMulti
                // distributes the controller's aggregate fluid back to each
                // member's 200 mB tank and flags the surviving cells for a
                // re-assembly attempt, so breaking one corner of a 3x3x3 can
                // settle into an adjacent 2x2x2 on the next server tick.
                //
                // Only regular thrusters (ThrusterBlockEntity) support this;
                // creative thrusters share AbstractThrusterBlock but have no
                // multiblock logic, hence the explicit instanceof check.
                BlockEntity be = level.getBlockEntity(pos);
                if (be instanceof ThrusterBlockEntity thruster && thruster.isMultiblock()) {
                    ThrusterBlockEntity controller = thruster.getControllerBE();
                    if (controller != null) {
                        controller.disassembleMulti();
                    }
                }

                ThrusterForceAttachment ship = ThrusterForceAttachment.get(level, pos);
                if (ship != null) {
                    ship.removeApplier((ServerLevel) level, pos);
                }
            }
        }

        super.onRemove(state, level, pos, newState, isMoving);
    }

    @Override
    public void neighborChanged(@Nonnull BlockState state, @Nonnull Level level, @Nonnull BlockPos pos, @Nonnull Block block, @Nonnull BlockPos fromPos, boolean isMoving) {
        if (level.isClientSide()) return;
        doRedstoneCheck(level, state, pos);
    }

    public void doRedstoneCheck(Level level, BlockState state, BlockPos pos) {
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (blockEntity instanceof AbstractThrusterBlockEntity thrusterBlockEntity) {
            int newRedstonePower = level.getBestNeighborSignal(pos);
            thrusterBlockEntity.setRedstoneInput(newRedstonePower);

            // For multiblock slaves, obstruction state lives on the
            // controller, and a block placed in front of any cell of the
            // cube should update the cube's scan as a whole. Route the
            // calculateObstruction call to the controller so nozzle-face
            // cells get re-scanned. Non-multi blocks (singles + creative
            // thrusters) keep the old per-position call.
            if (thrusterBlockEntity instanceof ThrusterBlockEntity t
                    && t.isMultiblock() && !t.isController()) {
                ThrusterBlockEntity ctrl = t.getControllerBE();
                if (ctrl != null) {
                    ctrl.calculateObstruction(
                            level,
                            ctrl.getBlockPos(),
                            ctrl.getBlockState().getValue(FACING));
                    return;
                }
            }

            thrusterBlockEntity.calculateObstruction(level, pos, state.getValue(FACING));
        }
    }

    @Override
    public BlockState rotate(@Nonnull BlockState state, @Nonnull Rotation rot) {
        return state.setValue(FACING, rot.rotate(state.getValue(FACING)));
    }

    @Override
    public BlockState mirror(@Nonnull BlockState state, @Nonnull Mirror mirrorIn) {
        return state.rotate(mirrorIn.getRotation(state.getValue(FACING)));
    }
}