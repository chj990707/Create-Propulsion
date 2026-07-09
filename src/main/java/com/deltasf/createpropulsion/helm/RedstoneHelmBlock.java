package com.deltasf.createpropulsion.helm;

import javax.annotation.Nullable;

import org.valkyrienskies.mod.common.VSGameUtilsKt;

import com.deltasf.createpropulsion.registries.PropulsionBlockEntities;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * A ship's helm that encodes the wheel angle as redstone signals: turning to
 * starboard powers the clockwise side, turning to port powers the
 * counter-clockwise side (0-15, one level per 24 degrees of wheel). Sit on it
 * while assembled to steer with A/D; hold sprint to turn the wheel faster.
 *
 * Ported from Valkyrien Sails' RedstoneHelmBlock
 * (MIT, https://github.com/Verquinox/valkyrien-sails).
 */
public class RedstoneHelmBlock extends BaseEntityBlock {
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;
    public static final BooleanProperty LEFT = BooleanProperty.create("left");
    public static final BooleanProperty RIGHT = BooleanProperty.create("right");

    private static final VoxelShape NORTH_SHAPE = Shapes.or(Block.box(5, 0, 2, 11, 16, 11), Block.box(0, 5, 11, 16, 21, 14));
    private static final VoxelShape SOUTH_SHAPE = Shapes.or(Block.box(5, 0, 5, 11, 16, 14), Block.box(0, 5, 2, 16, 21, 5));
    private static final VoxelShape EAST_SHAPE = Shapes.or(Block.box(5, 0, 5, 14, 16, 11), Block.box(2, 5, 0, 5, 21, 16));
    private static final VoxelShape WEST_SHAPE = Shapes.or(Block.box(2, 0, 5, 11, 16, 11), Block.box(11, 5, 0, 14, 21, 16));

    public RedstoneHelmBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any()
                .setValue(FACING, Direction.NORTH)
                .setValue(LEFT, false)
                .setValue(RIGHT, false));
    }

    @Override
    @SuppressWarnings("deprecation")
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        switch (state.getValue(FACING)) {
            case SOUTH: return SOUTH_SHAPE;
            case EAST: return EAST_SHAPE;
            case WEST: return WEST_SHAPE;
            case NORTH:
            default: return NORTH_SHAPE;
        }
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection());
    }

    @Override
    @SuppressWarnings("deprecation")
    public BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    @SuppressWarnings("deprecation")
    public BlockState mirror(BlockState state, Mirror mirror) {
        return state.rotate(mirror.getRotation(state.getValue(FACING)));
    }

    @Override
    @SuppressWarnings("deprecation")
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }
        if (!(level.getBlockEntity(pos) instanceof RedstoneHelmBlockEntity helm)) {
            return InteractionResult.sidedSuccess(false);
        }

        if (VSGameUtilsKt.isBlockInShipyard(level, pos)) {
            helm.sit(player);
        } else {
            //Off-ship helms can still be turned by hand: click for starboard, shift-click for port
            if (player.isShiftKeyDown()) {
                helm.rotateWheelLeft((ServerLevel) level, pos);
            } else {
                helm.rotateWheelRight((ServerLevel) level, pos);
            }
            updateNeighbours(state, level, pos);
            player.displayClientMessage(Component.literal("Angle: " + (helm.getWheelAngle() - RedstoneHelmBlockEntity.CENTER_ANGLE) + "\u00b0"), true);
        }
        return InteractionResult.sidedSuccess(false);
    }

    //=== Redstone ===

    @Override
    @SuppressWarnings("deprecation")
    public boolean isSignalSource(BlockState state) {
        return true;
    }

    @Override
    @SuppressWarnings("deprecation")
    public int getSignal(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
        if (level.getBlockEntity(pos) instanceof RedstoneHelmBlockEntity helm) {
            int wheelAngle = helm.getWheelAngle();
            if (direction == state.getValue(FACING).getClockWise()) {
                return Mth.clamp((wheelAngle - RedstoneHelmBlockEntity.CENTER_ANGLE) / 24, 0, 15);
            } else if (direction == state.getValue(FACING).getCounterClockWise()) {
                return Mth.clamp((RedstoneHelmBlockEntity.CENTER_ANGLE - wheelAngle) / 24, 0, 15);
            }
        }
        return 0;
    }

    public void updateNeighbours(BlockState state, Level level, BlockPos pos) {
        level.updateNeighborsAt(pos, this);
        level.updateNeighborsAt(pos.relative(state.getValue(FACING).getClockWise()), this);
        level.updateNeighborsAt(pos.relative(state.getValue(FACING).getCounterClockWise()), this);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean moved) {
        if (!moved && !state.is(newState.getBlock())) {
            updateNeighbours(state, level, pos);
        }
        if (state.hasBlockEntity() && !state.is(newState.getBlock())) {
            level.removeBlockEntity(pos);
        }
    }

    //=== Block entity ===

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return PropulsionBlockEntities.REDSTONE_HELM_BLOCK_ENTITY.create(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide) {
            return createTickerHelper(type, PropulsionBlockEntities.REDSTONE_HELM_BLOCK_ENTITY.get(), RedstoneHelmBlockEntity::clientTick);
        }
        return createTickerHelper(type, PropulsionBlockEntities.REDSTONE_HELM_BLOCK_ENTITY.get(), RedstoneHelmBlockEntity::serverTick);
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, LEFT, RIGHT);
    }
}
