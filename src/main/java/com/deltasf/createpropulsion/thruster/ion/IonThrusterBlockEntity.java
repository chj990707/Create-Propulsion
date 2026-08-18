package com.deltasf.createpropulsion.thruster.ion;

import com.deltasf.createpropulsion.PropulsionConfig;
import com.deltasf.createpropulsion.compat.PropulsionCompatibility;
import com.deltasf.createpropulsion.thruster.AbstractThrusterBlock;
import com.deltasf.createpropulsion.thruster.AbstractThrusterBlockEntity;
import com.deltasf.createpropulsion.thruster.thruster.ThrusterBlockEntity;
import com.simibubi.create.foundation.utility.CreateLang;
import net.createmod.catnip.lang.LangBuilder;
import net.minecraft.ChatFormatting;
import com.deltasf.createpropulsion.particles.ParticleTypes;
import com.deltasf.createpropulsion.particles.plasma.PlasmaParticleData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.energy.EnergyStorage;
import net.minecraftforge.energy.IEnergyStorage;

import javax.annotation.Nullable;
import java.util.List;

/**
 * Ion Thruster block entity.
 *
 * <p>Mirrors the single-block behaviour of {@link ThrusterBlockEntity} but
 * replaces the fuel/oxidizer fluid tanks with a Forge Energy ({@code IEnergyStorage})
 * buffer. On each update, the thrust produced is proportional to redstone
 * power and obstruction-free space (exactly like the fuel thruster's single
 * path), and the required Forge Energy is drained from the internal buffer;
 * if less than the required amount is available, thrust scales linearly with
 * the shortfall ratio so a half-full buffer produces half the thrust of a
 * fully-fed one.
 *
 * <p>Multiblock assembly (2x2x2 and 3x3x3 cubes) is intentionally NOT
 * implemented here yet. The class is laid out so a future multiblock
 * extension can be added alongside the fluid thruster's equivalent without
 * restructuring: {@link #updateThrust} delegates to {@link #updateSingleThrust}
 * via a size check on a {@code getWidth()} hook, and the energy buffer is
 * encapsulated behind {@link #getEnergyBuffer} / {@link #consumeEnergy} so a
 * future controller/slave pair can share an aggregate buffer the same way
 * {@code ThrusterBlockEntity} shares its aggregate fuel tank.
 *
 * <p>Forge Energy ({@code IEnergyStorage}) is exposed via
 * {@link ForgeCapabilities#ENERGY} on every face of the block EXCEPT the
 * nozzle (exhaust) face, which is {@code FACING.getOpposite()}. Non-directional
 * (side == null) queries, used by inspection tooling such as goggles, also
 * get the buffer. External machinery can push energy in but cannot pull it
 * out — the internal {@link EnergyStorage} is constructed with
 * {@code maxExtract == 0} and the thrust path uses the private
 * {@link IonEnergyStorage#consumeEnergy(int)} accessor so self-consumption
 * bypasses the extract limit without exposing it to neighbouring cables.
 */
public class IonThrusterBlockEntity extends AbstractThrusterBlockEntity {

    // Reused from ThrusterBlockEntity so the two variants share a consistent
    // thrust scale at their defaults. The thrust config multiplier is
    // independent per-variant, so worlds can retune ion vs. fluid balance
    // without affecting the shared baseline.
    public static final int BASE_MAX_THRUST = ThrusterBlockEntity.BASE_MAX_THRUST;

    private IonEnergyStorage energy;
    private LazyOptional<IEnergyStorage> energyCap;
    // Energy value restored from NBT. Loading runs BEFORE initialize(), at
    // which point the placeholder buffer still has capacity == 0, so naive
    // deserialisation would silently clamp the stored energy to zero. We
    // stash the raw int here and apply it once initialize() has built the
    // config-sized buffer.
    private int pendingEnergy = 0;

    // Goggle-tooltip / client-side sync support.
    //
    // The energy buffer changes for two distinct reasons:
    //   - external receive (a cable pushes energy in via receiveEnergy)
    //   - internal consumption (updateSingleThrust drains for thrust)
    //
    // Forge's EnergyStorage doesn't trigger any change notification on
    // receive, and updateSingleThrust runs on the parent's tick-rate
    // schedule, not every tick — so without an explicit sync path the
    // client sees stale data and goggle readouts freeze on the value
    // that was current at chunk-load. The fix is a tick()-level check
    // that broadcasts whenever the stored amount has drifted from the
    // last value the client was told about, throttled so that even a
    // farm of thrusters charging in parallel doesn't flood the network.
    private int lastBroadcastEnergy = -1;
    private int syncCooldown = 0;
    private static final int SYNC_INTERVAL_TICKS = 5;

    public IonThrusterBlockEntity(BlockEntityType<?> typeIn, BlockPos pos, BlockState state) {
        super(typeIn, pos, state);
        // Placeholder buffer: real capacity set in initialize() once the
        // server config is guaranteed loaded. The capability is always
        // non-null from construction on so adjacent cables can attach
        // immediately; they just can't store anything until the resize
        // lands on the first tick after attachment.
        // The setChanged callback ensures any external receive marks the
        // chunk dirty so saved energy is preserved across unloads.
        this.energy = new IonEnergyStorage(0, 0, this::setChanged);
        this.energyCap = LazyOptional.of(() -> energy);
    }

    @Override
    public void initialize() {
        int capacity = PropulsionConfig.ION_THRUSTER_CAPACITY.get();
        int maxReceive = PropulsionConfig.ION_THRUSTER_RF_PER_TICK.get()
                * PropulsionConfig.THRUSTER_TICKS_PER_UPDATE.get() * 4;
        if (energy.getMaxEnergyStored() != capacity) {
            // Re-invalidate any cached cap so consumers observe the new
            // instance (same pattern as ThrusterBlockEntity.invalidateMultiCap).
            if (energyCap != null) energyCap.invalidate();
            energy = new IonEnergyStorage(capacity, maxReceive, this::setChanged);
            energy.setEnergy(Math.min(pendingEnergy, capacity));
            energyCap = LazyOptional.of(() -> energy);
        }
        super.initialize();
    }

    @Override
    public void tick() {
        super.tick();
        // Server-side only: broadcast energy changes for goggle readouts.
        // Without this, a thruster being charged (no thrust running yet)
        // never broadcasts because updateThrust is the only existing sync
        // path and it short-circuits when not firing.
        if (level == null || level.isClientSide()) return;
        if (syncCooldown > 0) syncCooldown--;
        int current = energy.getEnergyStored();
        if (current != lastBroadcastEnergy && syncCooldown == 0) {
            lastBroadcastEnergy = current;
            syncCooldown = SYNC_INTERVAL_TICKS;
            sendData();
        }
    }

    // =====================================================================
    // Energy storage & capability routing
    // =====================================================================

    /** Returns the backing energy buffer. Exposed as a hook so a future
     *  multiblock controller/slave split can override this to return the
     *  controller's aggregate buffer when called on a slave. */
    protected IonEnergyStorage getEnergyBuffer() {
        return energy;
    }

    /** Internal-only consumption hook. Distinct from
     *  {@link IEnergyStorage#extractEnergy} (which is gated by
     *  {@code maxExtract == 0} for external callers) because the thruster's
     *  own thrust path is allowed to drain the buffer at whatever rate its
     *  thrust calculation dictates. */
    protected int consumeEnergy(int amount, boolean simulate) {
        return getEnergyBuffer().consumeEnergy(amount, simulate);
    }

    /** Public accessor — current stored energy in FE/RF. Used by the CC
     *  peripheral and any other read-only consumer that doesn't need the
     *  full {@link IEnergyStorage} surface. */
    public int getEnergyStored() {
        return getEnergyBuffer().getEnergyStored();
    }

    /** Public accessor — capacity of the energy buffer in FE/RF. */
    public int getMaxEnergyStored() {
        return getEnergyBuffer().getMaxEnergyStored();
    }

    @Override
    public <T> LazyOptional<T> getCapability(Capability<T> cap, @Nullable Direction side) {
        if (cap == ForgeCapabilities.ENERGY) {
            // Non-directional query (goggles, JEI previews, etc.): always
            // expose so inspection works from any angle. Matches the
            // fluid thruster's side==null branch in spirit.
            if (side == null) return energyCap.cast();
            // Directional (cable) query: accept on every face EXCEPT the
            // exhaust nozzle. FACING is the thrust direction; exhaust exits
            // FACING.getOpposite(), so that side is the one we refuse.
            Direction facing = getBlockState().getValue(AbstractThrusterBlock.FACING);
            if (side != facing.getOpposite()) return energyCap.cast();
            return LazyOptional.empty();
        }
        if (PropulsionCompatibility.CC_ACTIVE && computerBehaviour.isPeripheralCap(cap)) {
            return computerBehaviour.getPeripheralCapability().cast();
        }
        return super.getCapability(cap, side);
    }

    @Override
    public void invalidate() {
        super.invalidate();
        if (energyCap != null) {
            energyCap.invalidate();
        }
    }

    // =====================================================================
    // Thrust logic
    // =====================================================================

    /** Width hook for a future multiblock extension. Hardwired to 1 here,
     *  mirroring the fluid thruster's single-block-only initial state. */
    public int getWidth() {
        return 1;
    }

    @Override
    public void updateThrust(BlockState currentBlockState) {
        // Single-block path is the only path implemented in this iteration;
        // a future multiblock branch would route on getWidth() >= 2 to an
        // updateMultiThrust(..) the same way ThrusterBlockEntity does.
        updateSingleThrust(currentBlockState);
    }

    /** Drain RF proportional to redstone power & obstruction, set thrust to
     *  the matching fraction of BASE_MAX_THRUST * config multiplier. Kept
     *  structurally parallel to {@code ThrusterBlockEntity#updateSingleThrust}
     *  so the two variants balance against a shared baseline. */
    protected void updateSingleThrust(BlockState currentBlockState) {
        float thrust = 0;
        float currentPower = getPower();
        if (isWorking() && currentPower > 0) {
            float obstructionEffect = calculateObstructionEffect();
            float thrustPercentage = Math.min(currentPower, obstructionEffect);
            if (thrustPercentage > 0) {
                int tickRate = PropulsionConfig.THRUSTER_TICKS_PER_UPDATE.get();
                int needed = calculateEnergyConsumption(currentPower, tickRate);
                int consumed = consumeEnergy(needed, false);
                if (consumed > 0 && needed > 0) {
                    float ratio = (float) consumed / (float) needed;
                    float thrustMultiplier = PropulsionConfig.ION_THRUSTER_THRUST_MULTIPLIER.get().floatValue();
                    thrust = BASE_MAX_THRUST * thrustMultiplier * thrustPercentage * ratio;
                    // Note: client sync of the new energy level happens
                    // unconditionally in tick(), not here, so that buffer
                    // changes from external receive (cable charging) are
                    // covered too. See the syncCooldown logic above.
                }
            }
        }
        thrusterData.setThrust(thrust);
        isThrustDirty = false;
    }

    /** Energy demand per update tick. Shape matches the fluid thruster's
     *  {@code calculateFuelConsumption}: scales linearly in power and update
     *  window (tick rate). No fluid-specific multiplier because there is
     *  only one "fuel type" here (Forge Energy); the per-tick cost is
     *  configurable directly via {@code ION_THRUSTER_RF_PER_TICK}. */
    private int calculateEnergyConsumption(float powerPercentage, int tickRate) {
        int rfPerTick = PropulsionConfig.ION_THRUSTER_RF_PER_TICK.get();
        return (int) Math.ceil((double) rfPerTick * powerPercentage * tickRate);
    }

    @Override
    protected boolean isWorking() {
        // Parallels ThrusterBlockEntity#isWorking for the single-block case:
        // the thruster can produce thrust iff its fuel reservoir is non-empty.
        // We check "has any energy" rather than "has enough for a full
        // burst" so a nearly-empty buffer still produces scaled-down
        // thrust (updateSingleThrust handles the ratio).
        return getEnergyBuffer().getEnergyStored() > 0;
    }

    @Override
    protected double getNozzleOffsetFromCenter() {
        // Matches ThrusterBlockEntity so shared models emit plumes from the
        // correct position.
        return 0.95;
    }

    @Override
    @SuppressWarnings("unchecked")
    protected ParticleOptions createParticleOptions() {
        // Ion thrusters always emit plasma. The PlasmaParticle stack
        // (data class, particle, factory, JSON manifest, 9 sprite frames)
        // is already registered via ParticleTypes.PLASMA — this override
        // is the only wiring needed to swap the inherited PlumeParticleData
        // default. Mirrors CreativeThrusterBlockEntity#createParticleOptions
        // minus the plumeType branch, since ion thrusters don't expose a
        // selector.
        return new PlasmaParticleData((ParticleType<PlasmaParticleData>) ParticleTypes.getPlasmaType());
    }

    // =====================================================================
    // Goggles
    // =====================================================================

    @Override
    protected LangBuilder getGoggleStatus() {
        if (!isWorking()) {
            return CreateLang.builder()
                    .add(Component.translatable("createpropulsion.gui.goggles.ion_thruster.status.no_energy"))
                    .style(ChatFormatting.RED);
        }
        if (!isPowered()) {
            return CreateLang.builder()
                    .add(Component.translatable("createpropulsion.gui.goggles.thruster.status.not_powered"))
                    .style(ChatFormatting.GOLD);
        }
        if (getEmptyBlocks() == 0) {
            return CreateLang.builder()
                    .add(Component.translatable("createpropulsion.gui.goggles.thruster.obstructed"))
                    .style(ChatFormatting.RED);
        }
        return CreateLang.builder()
                .add(Component.translatable("createpropulsion.gui.goggles.thruster.status.working"))
                .style(ChatFormatting.GREEN);
    }

    @Override
    protected void addThrusterDetails(List<Component> tooltip, boolean isPlayerSneaking) {
        super.addThrusterDetails(tooltip, isPlayerSneaking);
        IonEnergyStorage buf = getEnergyBuffer();
        int stored = buf.getEnergyStored();
        int capacity = buf.getMaxEnergyStored();
        CreateLang.builder()
                .add(Component.translatable("createpropulsion.gui.goggles.ion_thruster.energy"))
                .text(": ")
                .add(CreateLang.number(stored))
                .text(" / ")
                .add(CreateLang.number(capacity))
                .space()
                .add(Component.translatable("createpropulsion.gui.goggles.ion_thruster.unit_fe"))
                .style(ChatFormatting.AQUA)
                .forGoggles(tooltip);
    }

    // =====================================================================
    // NBT
    // =====================================================================

    @Override
    protected void write(CompoundTag compound, boolean clientPacket) {
        super.write(compound, clientPacket);
        // Store the raw int rather than delegating to EnergyStorage's
        // NBT helpers because load() runs before initialize(), so on
        // load the buffer is still at its 0-capacity placeholder and
        // EnergyStorage#deserializeNBT would clamp the restored value
        // to zero. A flat int side-steps the ordering problem entirely.
        compound.putInt("Energy", energy.getEnergyStored());
    }

    @Override
    protected void read(CompoundTag compound, boolean clientPacket) {
        super.read(compound, clientPacket);
        pendingEnergy = compound.getInt("Energy");
        // If the buffer has already been resized (e.g. this is a client
        // sync packet arriving after initialize()), apply immediately.
        // Otherwise the value stays parked in pendingEnergy and initialize()
        // will pick it up.
        if (energy.getMaxEnergyStored() > 0) {
            energy.setEnergy(Math.min(pendingEnergy, energy.getMaxEnergyStored()));
        }
    }

    // =====================================================================
    // Internal storage type
    // =====================================================================

    /** Forge {@link EnergyStorage} subclass that:
     *  <ul>
     *    <li>Sets {@code maxExtract = 0} so neighbouring cables / machinery
     *        can never pull energy out; thrusters are sinks only.</li>
     *    <li>Adds a {@code consumeEnergy} accessor for the thruster's own
     *        thrust path, which bypasses the extract limit. This is the
     *        standard Forge idiom for internal-consumption-only buffers.</li>
     *    <li>Fires an {@code onChange} callback whenever a non-simulated
     *        receive actually deposits energy. The owning BE passes
     *        {@code BlockEntity::setChanged} so chunks save the new buffer
     *        level, and the BE's {@code tick()} picks up the delta and
     *        broadcasts it to clients on a short throttle for goggle
     *        readouts.</li>
     *  </ul>
     */
    public static class IonEnergyStorage extends EnergyStorage {
        private final Runnable onChange;

        public IonEnergyStorage(int capacity, int maxReceive, Runnable onChange) {
            super(capacity, maxReceive, 0);
            this.onChange = onChange;
        }

        @Override
        public int receiveEnergy(int maxReceive, boolean simulate) {
            int received = super.receiveEnergy(maxReceive, simulate);
            if (received > 0 && !simulate) onChange.run();
            return received;
        }

        /** Drain from the internal buffer without the {@code maxExtract}
         *  limit applied to external callers. Returns the amount actually
         *  drained, which may be less than {@code amount} when the buffer is
         *  nearly empty. */
        public int consumeEnergy(int amount, boolean simulate) {
            if (amount <= 0) return 0;
            int drained = Math.min(amount, this.energy);
            if (!simulate) {
                this.energy -= drained;
                if (drained > 0) onChange.run();
            }
            return drained;
        }

        /** Back-compat / convenience form that commits the drain. */
        public int consumeEnergy(int amount) {
            return consumeEnergy(amount, false);
        }

        /** Directly set the stored energy, clamped to the capacity. Used
         *  by {@link IonThrusterBlockEntity#initialize()} to preserve
         *  the existing fill level across a config-driven buffer resize. */
        public void setEnergy(int value) {
            this.energy = Math.max(0, Math.min(value, this.capacity));
        }
    }
}