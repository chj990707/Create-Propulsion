package com.deltasf.createpropulsion.helm;

import java.util.ArrayList;
import java.util.List;

import org.valkyrienskies.core.api.ships.LoadedServerShip;
import org.valkyrienskies.core.api.ships.ServerShip;
import org.valkyrienskies.mod.api.SeatedControllingPlayer;
import org.valkyrienskies.mod.common.VSGameUtilsKt;
import org.valkyrienskies.mod.common.ValkyrienSkiesMod;
import org.valkyrienskies.mod.common.entity.ShipMountingEntity;

import com.deltasf.createpropulsion.PropulsionConfig;
import com.deltasf.createpropulsion.network.PropulsionPackets;
import com.deltasf.createpropulsion.utility.AttachmentUtils;

import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.fml.loading.FMLEnvironment;

/**
 * Redstone helm block entity: seats the player, tracks the wheel angle
 * (0..720, centered at 360), and reads the seated player's steering input.
 * The block converts the angle into redstone signals; no forces are applied.
 *
 * Ported from Valkyrien Sails' BaseHelmBlockEntity / RedstoneHelmBlockEntity
 * (MIT, https://github.com/Verquinox/valkyrien-sails) with two QoL additions:
 * sprint-to-turn-faster and tick-interpolated wheel animation.
 */
public class RedstoneHelmBlockEntity extends BlockEntity {
    public static final int MAX_ANGLE = 720;
    public static final int CENTER_ANGLE = 360;

    private final List<ShipMountingEntity> seats = new ArrayList<>();
    private int wheelAngle = CENTER_ANGLE;

    //Sprint key state synced from the seated player via HelmSprintPacket.
    //Expires if the client stops refreshing it (dismount, disconnect, lag).
    private static final int SPRINT_TIMEOUT_TICKS = 30;
    private boolean sprintHeld = false;
    private long sprintExpiryTime = 0;

    //Client-side send bookkeeping for the sprint packet
    public boolean clientSprintSent = false;
    public long clientLastSprintSend = 0;

    //Client-side animation state. visualAngle chases wheelAngle at wheel speed each
    //client tick; the renderer lerps between prevVisualAngle and visualAngle with
    //partial ticks for a perfectly smooth wheel.
    public float visualAngle = CENTER_ANGLE;
    public float prevVisualAngle = CENTER_ANGLE;

    public RedstoneHelmBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    public static int wheelInterval() {
        return Math.max(1, PropulsionConfig.HELM_WHEEL_INTERVAL.get());
    }

    public static int sprintSteps() {
        return Math.max(1, PropulsionConfig.HELM_SPRINT_STEPS.get());
    }

    public int getWheelAngle() {
        return wheelAngle;
    }

    //=== Ticking ===

    public static void serverTick(Level level, BlockPos pos, BlockState state, RedstoneHelmBlockEntity helm) {
        if (level.isClientSide) return;

        if (VSGameUtilsKt.isBlockInShipyard(level, pos)) {
            ServerLevel serverLevel = (ServerLevel) level;
            ServerShip serverShip = AttachmentUtils.getShipAt(serverLevel, pos);
            if (serverShip instanceof LoadedServerShip ship) {
                SeatedControllingPlayer control = ship.getAttachment(SeatedControllingPlayer.class);
                if (control != null && state.getBlock() instanceof RedstoneHelmBlock redstoneHelm) {
                    //Holding sprint turns the wheel several steps per tick. VS2's own
                    //sprintOn never activates while seated (see HelmSprintPacket), so
                    //the synced key state is the one that actually drives this.
                    boolean sprinting = helm.isSprintHeld(level) || control.getSprintOn();
                    int steps = sprinting ? sprintSteps() : 1;
                    boolean rotated = false;
                    if (control.getLeftImpulse() < 0) {
                        for (int i = 0; i < steps; i++) rotated |= helm.rotateWheelRight(serverLevel, pos);
                    } else if (control.getLeftImpulse() > 0) {
                        for (int i = 0; i < steps; i++) rotated |= helm.rotateWheelLeft(serverLevel, pos);
                    }
                    if (rotated) {
                        redstoneHelm.updateNeighbours(state, level, pos);
                    }
                }
            }
        }

        //Reflect the wheel position on the LEFT/RIGHT blockstate for the indicator texture
        if (state.getBlock() instanceof RedstoneHelmBlock) {
            BlockState newState = state
                    .setValue(RedstoneHelmBlock.LEFT, helm.wheelAngle > CENTER_ANGLE)
                    .setValue(RedstoneHelmBlock.RIGHT, helm.wheelAngle < CENTER_ANGLE);
            if (!newState.equals(state)) {
                level.setBlock(pos, newState, 3);
            }
        }
    }

    public static void clientTick(Level level, BlockPos pos, BlockState state, RedstoneHelmBlockEntity helm) {
        helm.prevVisualAngle = helm.visualAngle;
        float diff = helm.wheelAngle - helm.visualAngle;
        if (diff != 0) {
            //Fastest legitimate wheel speed in degrees per tick
            float maxStep = wheelInterval() * sprintSteps();
            float step = Mth.clamp(diff, -maxStep, maxStep);
            //Catch up quickly after large desyncs instead of slowly grinding over
            if (Math.abs(diff) > 90.0f) step = diff * 0.5f;
            helm.visualAngle += step;
        }

        if (FMLEnvironment.dist.isClient()) {
            HelmClientHandler.sprintKeyTick(level, pos, state, helm);
        }
    }

    public void setSyncedWheelAngle(int angle) {
        this.wheelAngle = angle;
    }

    //=== Sprint sync (see HelmSprintPacket) ===

    /** Server side: accepts sprint key state from the player seated at this helm. */
    public void updateSprint(ServerPlayer player, boolean sprinting) {
        if (level == null || level.isClientSide) return;
        if (!(player.getVehicle() instanceof ShipMountingEntity seat) || !seats.contains(seat)) return;
        sprintHeld = sprinting;
        sprintExpiryTime = level.getGameTime() + SPRINT_TIMEOUT_TICKS;
    }

    private boolean isSprintHeld(Level level) {
        return sprintHeld && level.getGameTime() <= sprintExpiryTime;
    }

    /** Where this helm's seat entity sits, in shipyard coordinates. */
    public static Vec3 seatPosition(BlockPos pos, Direction facing) {
        if (facing == Direction.NORTH) {
            return new Vec3(pos.getX() + 0.5, pos.getY() + 0.125, pos.getZ() + 1.3125);
        } else if (facing == Direction.SOUTH) {
            return new Vec3(pos.getX() + 0.5, pos.getY() + 0.125, pos.getZ() - 0.3125);
        } else if (facing == Direction.EAST) {
            return new Vec3(pos.getX() - 0.3125, pos.getY() + 0.125, pos.getZ() + 0.5);
        } else {
            return new Vec3(pos.getX() + 1.3125, pos.getY() + 0.125, pos.getZ() + 0.5);
        }
    }

    //=== Wheel rotation (server side) ===

    public boolean rotateWheelRight(ServerLevel level, BlockPos pos) {
        boolean success = false;
        if (wheelAngle - wheelInterval() >= 0) {
            wheelAngle -= wheelInterval();
            playWheelSounds(level, pos);
            setChanged();
            success = true;
        }
        syncAngle(level, pos);
        return success;
    }

    public boolean rotateWheelLeft(ServerLevel level, BlockPos pos) {
        boolean success = false;
        if (wheelAngle + wheelInterval() <= MAX_ANGLE) {
            wheelAngle += wheelInterval();
            playWheelSounds(level, pos);
            setChanged();
            success = true;
        }
        syncAngle(level, pos);
        return success;
    }

    private void syncAngle(ServerLevel level, BlockPos pos) {
        PropulsionPackets.sendToTracking(new SyncHelmWheelPacket(pos, wheelAngle), level.getChunkAt(pos));
    }

    private void playWheelSounds(Level level, BlockPos pos) {
        if ((double) wheelAngle / MAX_ANGLE == 0.5) {
            level.playSound(null, pos.below(), SoundEvents.BAMBOO_WOOD_BUTTON_CLICK_ON,
                    SoundSource.BLOCKS, 1.5f, level.getRandom().nextFloat() * 0.1F + 0.9F);
            level.playSound(null, pos.below(), SoundEvents.ARMOR_EQUIP_CHAIN,
                    SoundSource.BLOCKS, 0.1f, level.getRandom().nextFloat() * 0.1F + 0.9F);
        } else if (wheelAngle == MAX_ANGLE || wheelAngle == 0) {
            level.playSound(null, pos.below(), SoundEvents.BAMBOO_WOOD_BUTTON_CLICK_ON,
                    SoundSource.BLOCKS, 1.5f, level.getRandom().nextFloat() * 0.1F + 0.9F);
        }
    }

    //=== Seat handling ===

    public boolean sit(Player player) {
        if (!(level instanceof ServerLevel serverLevel)) return false;

        //Clear out previous seats: a new rider takes over the helm
        for (int i = seats.size() - 1; i >= 0; i--) {
            if (seats.get(i).isAlive()) {
                seats.get(i).kill();
            }
            seats.remove(i);
        }

        ShipMountingEntity seat = spawnSeat(getBlockPos(), getBlockState(), serverLevel);
        boolean ride = player.startRiding(seat, false);
        if (ride) {
            seats.add(seat);
        } else {
            seat.kill();
        }
        return ride;
    }

    private ShipMountingEntity spawnSeat(BlockPos pos, BlockState state, ServerLevel level) {
        Vec3 mounterPos = seatPosition(pos, state.getValue(RedstoneHelmBlock.FACING));

        ShipMountingEntity entity = ValkyrienSkiesMod.SHIP_MOUNTING_ENTITY_TYPE.create(level);
        assert entity != null;
        entity.setPos(mounterPos.x(), mounterPos.y(), mounterPos.z());
        entity.lookAt(EntityAnchorArgument.Anchor.EYES, new Vec3(pos.getX(), pos.getY(), pos.getZ()));
        entity.move(MoverType.SELF, new Vec3(0, 0, 0));
        entity.setController(true);
        level.addFreshEntityWithPassengers(entity);
        return entity;
    }

    @Override
    public void setRemoved() {
        if (level != null && !level.isClientSide) {
            for (int i = seats.size() - 1; i >= 0; i--) {
                seats.get(i).kill();
            }
            seats.clear();
        }
        super.setRemoved();
    }

    //=== Save / load / sync ===

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putInt("wheel_angle", wheelAngle);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        if (tag.contains("wheel_angle")) {
            wheelAngle = tag.getInt("wheel_angle");
        }
        visualAngle = wheelAngle;
        prevVisualAngle = wheelAngle;
    }

    @Override
    public CompoundTag getUpdateTag() {
        return saveWithoutMetadata();
    }
}
