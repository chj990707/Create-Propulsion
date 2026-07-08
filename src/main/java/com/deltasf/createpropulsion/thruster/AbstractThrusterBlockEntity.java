package com.deltasf.createpropulsion.thruster;

import com.deltasf.createpropulsion.PropulsionConfig;
import com.deltasf.createpropulsion.compat.PropulsionCompatibility;
import com.deltasf.createpropulsion.compat.computercraft.ComputerBehaviour;
import com.deltasf.createpropulsion.particles.ParticleTypes;
import com.deltasf.createpropulsion.particles.plume.PlumeParticleData;
import com.deltasf.createpropulsion.utility.GoggleUtils;
import com.deltasf.createpropulsion.utility.math.MathUtility;
import com.simibubi.create.api.equipment.goggles.IHaveGoggleInformation;
import com.simibubi.create.compat.computercraft.AbstractComputerBehaviour;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.utility.CreateLang;
import net.createmod.catnip.lang.LangBuilder;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Math;
import org.joml.Matrix4dc;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.valkyrienskies.core.api.ships.ClientShip;
import org.valkyrienskies.mod.common.VSGameUtilsKt;
import org.valkyrienskies.mod.common.util.VectorConversionsMCKt;

import java.util.List;

@SuppressWarnings({"deprecation", "unchecked"})
public abstract class AbstractThrusterBlockEntity extends SmartBlockEntity implements IHaveGoggleInformation {
    //Constants
    protected static final int OBSTRUCTION_LENGTH = 10;
    protected static final int TICKS_PER_ENTITY_CHECK = 5;
    private static final float PARTICLE_VELOCITY = 4;
    private static final double SHIP_VELOCITY_INHERITANCE = 0.5;

    protected static final float LOWEST_POWER_THRESHOLD = 5.0f / 15.0f;

    //Common State
    protected ThrusterData thrusterData;
    protected int emptyBlocks;
    protected boolean isThrustDirty = false;

    protected ThrusterSoundInstance soundInstance;

    //Ticking
    private int currentTick = 0;
    private int clientTick = 0;
    private float particleSpawnAccumulator = 0.0f;

    //CC Peripheral
    public AbstractComputerBehaviour computerBehaviour;
    public enum ControlMode {
        NORMAL,
        PERIPHERAL
    }

    protected ControlMode controlMode = ControlMode.NORMAL;
    protected int redstoneInput = 0;
    protected float digitalInput = 0.0f;

    public AbstractThrusterBlockEntity(BlockEntityType<?> typeIn, BlockPos pos, BlockState state) {
        super(typeIn, pos, state);
        thrusterData = new ThrusterData();
    }

    @SuppressWarnings("null")
    @Override
    public void initialize() {
        super.initialize();
        if (!level.isClientSide) {
            BlockState state = getBlockState();
            calculateObstruction(level, worldPosition, state.getValue(AbstractThrusterBlock.FACING));

            ThrusterForceAttachment ship = ThrusterForceAttachment.get(level, worldPosition);
            if (ship != null) {
                ThrusterData data = this.getThrusterData();
                data.setDirection(VectorConversionsMCKt.toJOMLD(state.getValue(AbstractThrusterBlock.FACING).getNormal()));
                data.setThrust(0);
                ThrusterForceApplier applier = new ThrusterForceApplier(data);
                ship.addApplier(worldPosition, applier);
            }

            Block block = getBlockState().getBlock();
            if (block instanceof AbstractThrusterBlock) {
                ((AbstractThrusterBlock) block).doRedstoneCheck(level, getBlockState(), worldPosition);
            }
        }
        // Client-side sound instances are created lazily from tick() via
        // ensureSoundInstance() rather than here, so they can stop themselves
        // (releasing their sound channel) while idle and be revived later.
    }

    //Control logic

    public void setRedstoneInput(int power) {
        if (redstoneInput != power) {
            redstoneInput = power;
            if (controlMode == ControlMode.NORMAL) {
                dirtyThrust();
                notifyUpdate();
            }
        }
    }

    public void setDigitalInput(float power) {
        float clamped = org.joml.Math.clamp(0.0f, 1.0f, power);
        if (java.lang.Math.abs(digitalInput - clamped) > 1e-4) {
            digitalInput = clamped;
            if (controlMode == ControlMode.PERIPHERAL) {
                dirtyThrust();
                notifyUpdate();
            }
        }
    }

    public void setControlMode(ControlMode mode) {
        if (this.controlMode != mode) {
            this.controlMode = mode;
            dirtyThrust();
            notifyUpdate();
        }
    }

    public float getPower() {
        if (controlMode == ControlMode.PERIPHERAL) {
            return digitalInput;
        }
        return redstoneInput / 15.0f;
    }

    public int getLegacyPowerInt() {
        return (int) Math.round(getPower() * 15);
    }

    @Override
    public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
        if (PropulsionCompatibility.CC_ACTIVE) {
            behaviours.add(computerBehaviour = new ComputerBehaviour(this));
        }
        behaviours.add(new ThrusterDamager(this));
    }

    @SuppressWarnings("null")
    @Override
    public void tick() {
        if (this.isRemoved()) {
            return;
        }
        //This part should ACTUALLY fix the issue with particle emission
        if (level.getBlockState(worldPosition).getBlock() != this.getBlockState().getBlock()) {
            this.setRemoved();
            return;
        }

        super.tick();
        BlockState currentBlockState = getBlockState();
        if (level.isClientSide) {
            ensureSoundInstance();
            if (shouldEmitParticles()) {
                emitParticles(level, worldPosition, currentBlockState);
            }
            return;
        }
        currentTick++;
        int tick_rate = PropulsionConfig.THRUSTER_TICKS_PER_UPDATE.get();

        //Periodically recalculate obstruction
        if (currentTick % (tick_rate * 2) == 0) {
            int previousEmptyBlocks = emptyBlocks;
            calculateObstruction(level, worldPosition, currentBlockState.getValue(AbstractThrusterBlock.FACING));
            if (previousEmptyBlocks != emptyBlocks) {
                isThrustDirty = true;
                setChanged();
                level.sendBlockUpdated(worldPosition, currentBlockState, currentBlockState, Block.UPDATE_CLIENTS);
            }
        }

        //Update thrust periodically or when marked dirty
        if (isThrustDirty || currentTick % tick_rate == 0) {
            updateThrust(currentBlockState);
        }
    }

    public abstract void updateThrust(BlockState currentBlockState);

    protected abstract boolean isWorking();

    protected abstract LangBuilder getGoggleStatus();

    public ThrusterData getThrusterData() {
        return thrusterData;
    }

    public int getEmptyBlocks() {
        return emptyBlocks;
    }

    public void dirtyThrust() {
        isThrustDirty = true;
    }

    protected boolean shouldEmitParticles() {
        return isPowered() && isWorking();
    }

    /** The audio counterpart of shouldEmitParticles(): a thruster only hums
     *  while it is actually producing thrust -- powered AND working (valid
     *  fuel, and oxidizer for multiblocks). Read by ThrusterSoundInstance
     *  every tick and by ensureSoundInstance() as the revival gate. */
    protected boolean shouldPlaySound() {
        return isPowered() && isWorking();
    }

    protected boolean shouldDamageEntities() {
        return PropulsionConfig.THRUSTER_DAMAGE_ENTITIES.get() && isPowered() && isWorking();
    }

    protected void addSpecificGoggleInfo(List<Component> tooltip, boolean isPlayerSneaking) {}

    protected boolean isPowered() {
        return getPower() > MathUtility.epsilon;
    }

    protected float calculateObstructionEffect() {
        return (float) emptyBlocks / (float) OBSTRUCTION_LENGTH;
    }

    protected ParticleOptions createParticleOptions() {
        return new PlumeParticleData((ParticleType<PlumeParticleData>) ParticleTypes.getPlumeType());
    }

    /** Optional extra offset applied to the particle spawn position, in
     *  block-space. Default: zero. Subclasses can override to shift where
     *  plumes originate without affecting their velocity or ejection
     *  direction. Used by multiblock thrusters to draw per-cell plumes a
     *  few pixels toward the cube's axis.
     *
     *  @param oppositeDirection the exhaust direction (FACING.getOpposite()),
     *                           passed so overrides can reason about which
     *                           axes are perpendicular to the nozzle. */
    protected Vector3d getExtraParticleOriginOffset(Direction oppositeDirection) {
        return new Vector3d();
    }

    protected abstract double getNozzleOffsetFromCenter();

    public void emitParticles(Level level, BlockPos pos, BlockState state) {
        if (emptyBlocks == 0) return;
        float power = getPower();

        double particleCountMultiplier = org.joml.Math.clamp(0.0, 2.0, PropulsionConfig.THRUSTER_PARTICLE_COUNT_MULTIPLIER.get());
        if (particleCountMultiplier <= 0) return;

        clientTick++;
        if (power < LOWEST_POWER_THRESHOLD && clientTick % 2 == 0) {
            clientTick = 0;
            return;
        }

        this.particleSpawnAccumulator += particleCountMultiplier;

        int particlesToSpawn = (int) this.particleSpawnAccumulator;
        if (particlesToSpawn == 0) return;

        float visualPower = Math.max(power, LOWEST_POWER_THRESHOLD);

        this.particleSpawnAccumulator -= particlesToSpawn;
        Direction direction = state.getValue(AbstractThrusterBlock.FACING);
        Direction oppositeDirection = direction.getOpposite();

        double currentNozzleOffset = getNozzleOffsetFromCenter();
        Vector3d additionalVel = new Vector3d();
        ClientShip ship = VSGameUtilsKt.getShipObjectManagingPos((ClientLevel) level, pos);
        if (ship != null) {
            Vector3dc shipWorldVelocityJOML = ship.getVelocity();
            Matrix4dc transform = ship.getRenderTransform().getShipToWorld();
            Matrix4dc invTransform = ship.getRenderTransform().getWorldToShip();

            Vector3d shipVelocity = invTransform.transformDirection(new Vector3d(shipWorldVelocityJOML));

            Vector3d particleEjectionUnitVecJOML = transform.transformDirection(VectorConversionsMCKt.toJOMLD(oppositeDirection.getNormal()));

            double shipVelComponentAlongRotatedEjection = shipWorldVelocityJOML.dot(particleEjectionUnitVecJOML);
            if (shipVelComponentAlongRotatedEjection > 0.0) {
                Vector3d normalizedVelocity = new Vector3d();
                shipWorldVelocityJOML.normalize(normalizedVelocity);
                double shipVelComponentAlongRotatedEjectionNormalized = normalizedVelocity.dot(particleEjectionUnitVecJOML);
                //Effect is used to smooth transition from no additional offset/vel to full in range [0, 1]
                double effect = org.joml.Math.clamp(0.0, 1, shipVelComponentAlongRotatedEjectionNormalized);
                double additionalOffset = (shipVelComponentAlongRotatedEjection) * PropulsionConfig.THRUSTER_PARTICLE_OFFSET_INCOMING_VEL_MODIFIER.get();
                currentNozzleOffset += additionalOffset * effect;
                additionalVel = new Vector3d(shipVelocity).mul(SHIP_VELOCITY_INHERITANCE * effect);
            }
        }

        // Optional perpendicular nudge supplied by subclasses (used by
        // multiblock thrusters to pull per-cell plumes a little toward the
        // cube's axis for a cleaner silhouette). Singles override nothing
        // and get (0, 0, 0) here, preserving exact pre-existing positions.
        Vector3d extraOriginOffset = getExtraParticleOriginOffset(oppositeDirection);

        double particleX = pos.getX() + 0.5 + oppositeDirection.getStepX() * currentNozzleOffset + extraOriginOffset.x;
        double particleY = pos.getY() + 0.5 + oppositeDirection.getStepY() * currentNozzleOffset + extraOriginOffset.y;
        double particleZ = pos.getZ() + 0.5 + oppositeDirection.getStepZ() * currentNozzleOffset + extraOriginOffset.z;

        Vector3d particleVelocity = new Vector3d(oppositeDirection.getStepX(), oppositeDirection.getStepY(), oppositeDirection.getStepZ())
                .mul(PARTICLE_VELOCITY * visualPower).add(additionalVel);

        ParticleOptions particleData = createParticleOptions();

        //Spawn the calculated number of particles.
        for (int i = 0; i < particlesToSpawn; i++) {
            level.addParticle(particleData, true,
                    particleX, particleY, particleZ,
                    particleVelocity.x, particleVelocity.y, particleVelocity.z);
        }
    }

    public void calculateObstruction(Level level, BlockPos pos, Direction forwardDirection){
        //Starting from the block behind and iterate OBSTRUCTION_LENGTH blocks in that direction
        //Can't really use level.clip as we explicitly want to check for obstruction only in ship space
        int oldEmptyBlocks = this.emptyBlocks;
        for (emptyBlocks = 0; emptyBlocks < OBSTRUCTION_LENGTH; emptyBlocks++){
            BlockPos checkPos = pos.relative(forwardDirection.getOpposite(), emptyBlocks + 1);
            BlockState state = level.getBlockState(checkPos);
            if (!(state.isAir() || !state.isSolid())) break;
        }
        if (oldEmptyBlocks != this.emptyBlocks) { //Only set dirty if it actually changed
            isThrustDirty = true;
        }
    }

    @Override
    public boolean addToGoggleTooltip(List<Component> tooltip, boolean isPlayerSneaking) {
        boolean wasThrustDirty = isThrustDirty;
        calculateObstruction(getLevel(), worldPosition, getBlockState().getValue(AbstractThrusterBlock.FACING));
        isThrustDirty = wasThrustDirty;

        CreateLang.builder().add(Component.translatable("createpropulsion.gui.goggles.thruster.status")).text(":").space().add(getGoggleStatus()).forGoggles(tooltip);

        addThrusterDetails(tooltip, isPlayerSneaking);

        if (controlMode == ControlMode.PERIPHERAL) {
            CreateLang.builder().add(Component.translatable("createpropulsion.gui.goggles.cc.peripheral_controlled")).style(ChatFormatting.GRAY).forGoggles(tooltip);
        }

        return true;
    }

    protected void addThrusterDetails(List<Component> tooltip, boolean isPlayerSneaking) {
        // Use getEmptyBlocks() (not the raw field) so multiblock subclasses
        // can supply an aggregate value across the whole cube. Singles
        // return their local field unchanged; multi controllers return the
        // averaged nozzle-face-cell count (see
        // ThrusterBlockEntity#getEmptyBlocks). This fixes rear/interior
        // multiblock cells reporting stale "obstructed" readings on the
        // goggle tooltip.
        int empty = getEmptyBlocks();
        float efficiency = 100;
        ChatFormatting tooltipColor = ChatFormatting.GREEN;
        if (empty < OBSTRUCTION_LENGTH) {
            efficiency = calculateObstructionEffect() * 100;
            tooltipColor = GoggleUtils.efficiencyColor(efficiency);
            CreateLang.builder().add(Component.translatable("createpropulsion.gui.goggles.thruster.obstructed")).space().add(CreateLang.text(GoggleUtils.makeObstructionBar(empty, OBSTRUCTION_LENGTH))).style(tooltipColor).forGoggles(tooltip);
        }

        CreateLang.builder().add(Component.translatable("createpropulsion.gui.goggles.thruster.efficiency")).text(": ").add(CreateLang.number(efficiency)).add(CreateLang.text("%")).style(tooltipColor).forGoggles(tooltip);
    }


    @Override
    protected void write(CompoundTag compound, boolean clientPacket) {
        super.write(compound, clientPacket);
        compound.putInt("emptyBlocks", emptyBlocks);
        compound.putInt("currentTick", currentTick);

        compound.putInt("RedstoneInput", redstoneInput);
        compound.putFloat("DigitalInput", digitalInput);
        compound.putInt("ControlMode", controlMode.ordinal());
    }

    @Override
    protected void read(CompoundTag compound, boolean clientPacket) {
        super.read(compound, clientPacket);
        emptyBlocks = compound.getInt("emptyBlocks");
        currentTick = compound.getInt("currentTick");

        redstoneInput = compound.getInt("RedstoneInput");
        digitalInput = compound.getFloat("DigitalInput");
        if (compound.contains("ControlMode")) {
            controlMode = ControlMode.values()[compound.getInt("ControlMode")];
        }
    }

    // Cooldown between sound revival attempts. A freshly queued instance
    // sits in the engine's queue for a tick before isActive() reports it,
    // so retrying every tick would stack duplicate sounds.
    private int soundRetryCooldown = 0;

    /** Client-side: (re)creates the looping sound instance if this thruster
     *  should currently emit one and none is LIVE IN THE ENGINE. Liveness
     *  must be checked against the engine itself (isActive), not just the
     *  instance's own isStopped flag: the engine drops sounds externally on
     *  volume slider changes, F3+T, resource reloads, and device changes
     *  without ever setting that flag. Idle thrusters are not revived at
     *  all -- between this gate and the instance's own idle-stop, a
     *  thruster at zero power costs zero of Minecraft's 247 sound
     *  channels. */
    public void ensureSoundInstance() {
        if (level == null || !level.isClientSide) return;
        if (!shouldEmitSound() || !shouldPlaySound()) return;
        SoundManager soundManager = Minecraft.getInstance().getSoundManager();
        if (soundInstance != null && !soundInstance.isStopped() && soundManager.isActive(soundInstance)) {
            soundRetryCooldown = 0;
            return;
        }
        if (soundRetryCooldown > 0) {
            soundRetryCooldown--;
            return;
        }
        soundRetryCooldown = 4;
        soundInstance = new ThrusterSoundInstance(this);
        soundManager.queueTickingSound(soundInstance);
    }

    /** Whether this block entity should host a sound instance at all.
     *  Multiblock-capable thrusters override this to exclude non-controller
     *  cells, so a 3x3x3 cube costs one sound channel instead of 27. */
    public boolean shouldEmitSound() {
        return true;
    }

    @Override
    public void invalidate() {
        super.invalidate();
        if (soundInstance != null) Minecraft.getInstance().getSoundManager().stop(soundInstance);
    }
}