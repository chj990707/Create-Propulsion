package com.deltasf.createpropulsion.thruster.thruster;

import com.deltasf.createpropulsion.PropulsionConfig;
import com.deltasf.createpropulsion.compat.PropulsionCompatibility;
import com.deltasf.createpropulsion.registries.PropulsionFluids;
import com.deltasf.createpropulsion.thruster.AbstractThrusterBlock;
import com.deltasf.createpropulsion.thruster.AbstractThrusterBlockEntity;
import com.deltasf.createpropulsion.thruster.FluidThrusterProperties;
import com.deltasf.createpropulsion.thruster.ThrusterFuelManager;
import com.simibubi.create.foundation.blockEntity.IMultiBlockEntityContainer;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.fluid.SmartFluidTankBehaviour;
import com.simibubi.create.foundation.fluid.CombinedTankWrapper;
import com.simibubi.create.foundation.utility.CreateLang;
import net.createmod.catnip.lang.LangBuilder;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.IFluidTank;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.fluids.capability.IFluidHandler.FluidAction;
import net.minecraftforge.registries.ForgeRegistries;
import org.joml.Vector3d;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * Regular thruster block entity. Acts as either a single-block thruster
 * (width == 1) or as one cell of a Multiblock Thruster (width == 2 or 3).
 *
 * Multiblock assembly is a strict cube of thrusters all facing the same
 * direction. Because Create's generic ConnectivityHandler only filters by
 * BlockEntityType -- it has no way to reject a south-facing neighbor in an
 * otherwise north-facing group -- the cube search is done here by hand.
 * Controller/slave layout and field names mirror FluidTankBlockEntity.
 *
 * Singles are unchanged in behavior: oxidizer is not required and not drained.
 * Only multiblock thrusters consume oxidizer alongside fuel.
 */
public class ThrusterBlockEntity extends AbstractThrusterBlockEntity
        implements IMultiBlockEntityContainer.Fluid {

    public static final float BASE_FUEL_CONSUMPTION = 2;
    public static final int BASE_MAX_THRUST = 600000;
    public static final int BASE_CAPACITY = 200;
    public static final int MAX_WIDTH = 3;

    // The nested type IMultiBlockEntityContainer.Fluid inherited from the
    // interface we implement shadows a bare `Fluid` identifier in this class's
    // body (Java resolves inherited nested types ahead of imports). FQN works
    // around that. The tag itself sits under `forge:oxidizer` so other mods
    // can contribute compatible oxidizers via datapack.
    public static final TagKey<net.minecraft.world.level.material.Fluid> OXIDIZER_TAG = TagKey.create(
            ForgeRegistries.FLUIDS.getRegistryKey(),
            ResourceLocation.fromNamespaceAndPath("forge", "oxidizer"));

    // ---- Tanks ----
    public SmartFluidTankBehaviour tank;
    public SmartFluidTankBehaviour oxidizerTank;

    // ---- Multiblock state ----
    // Null when this BE is a controller (or a lone single). Otherwise the
    // lowest-corner position of the cube this BE is a slave of.
    @Nullable
    protected BlockPos controllerPos;
    // Cube side length. 1 == single, 2 == medium, 3 == large.
    protected int width = 1;
    // Fresh BEs start with this true so they try assembly on their first
    // server tick. Persisted BEs override it from NBT (normally false once a
    // multi has already settled). Cleared inside tick() after tryAssemble.
    protected boolean updateConnectivity = true;

    // Cached capability handed to adjacent pipes when in multi mode: a
    // CombinedTankWrapper over controller's fuel + oxidizer with
    // enforceVariety(). The SmartFluidTank validators below make the routing
    // unambiguous even when one tank is empty.
    private LazyOptional<IFluidHandler> multiFluidCap;

    // Transient one-shot guard consumed by disassembleMulti(). Set by
    // preventNextDisassembly() right before a relocation (e.g. the
    // PhysicsAssembler copying the cube into shipyard coordinates) so the
    // old BE doesn't tear the multi down on its way out. Not persisted --
    // after the block's NBT is copied to the new location the new BE starts
    // with this false, which is what we want.
    private transient boolean suppressNextDisassembly = false;

    public ThrusterBlockEntity(BlockEntityType<?> typeIn, BlockPos pos, BlockState state) {
        super(typeIn, pos, state);
    }

    @Override
    public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
        super.addBehaviours(behaviours);
        tank = SmartFluidTankBehaviour.single(this, BASE_CAPACITY);
        behaviours.add(tank);
        // INPUT (not the default TYPE) gives the oxidizer tank a distinct
        // behaviour key so its NBT/lookup do not collide with the fuel tank.
        oxidizerTank = new SmartFluidTankBehaviour(
                SmartFluidTankBehaviour.INPUT, this, 1, BASE_CAPACITY, false);
        behaviours.add(oxidizerTank);

        // Per-tank fluid validators. Used by CombinedTankWrapper.enforceVariety
        // to route fuel vs oxidizer to the correct internal tank. The oxidizer
        // validator falls back to a direct fluid comparison so routing still
        // works if the forge:oxidizer tag data file is missing.
        tank.getPrimaryHandler()
                .setValidator(stack -> ThrusterFuelManager.getProperties(stack.getFluid()) != null);
        oxidizerTank.getPrimaryHandler().setValidator(this::isOxidizer);
    }

    // =====================================================================
    // Multiblock lifecycle
    // =====================================================================

    public boolean isMultiblock() {
        return width > 1;
    }

    @Override
    public boolean isController() {
        return controllerPos == null;
    }

    @SuppressWarnings("unchecked")
    @Override
    public ThrusterBlockEntity getControllerBE() {
        if (isController() || !hasLevel()) return this;
        BlockEntity be = level.getBlockEntity(controllerPos);
        return be instanceof ThrusterBlockEntity t ? t : null;
    }

    /**
     * Chunk-culling bounds for the BER. A single controller is a 1x1x1 cube
     * at its position; a multi controller's BER draws a scaled model that
     * covers width^3 blocks starting at this position, so the render AABB
     * must span the whole cube or chunk culling will clip the rear half of
     * the model whenever the player looks at the cube from the side.
     *
     * Slaves render nothing (see ThrusterRenderer), so their bounding box
     * can stay at the default 1x1x1 -- we only grow it for controllers.
     */
    @Override
    public AABB getRenderBoundingBox() {
        if (isController() && isMultiblock()) {
            return new AABB(
                    worldPosition.getX(), worldPosition.getY(), worldPosition.getZ(),
                    worldPosition.getX() + width,
                    worldPosition.getY() + width,
                    worldPosition.getZ() + width);
        }
        return super.getRenderBoundingBox();
    }

    @Override
    public void tick() {
        super.tick();
        if (level == null || level.isClientSide) return;
        if (updateConnectivity) {
            updateConnectivity = false;
            // Only controllers (or singles) drive assembly. Slaves do nothing
            // here; their state is managed by their controller.
            if (isController() && !isMultiblock()) {
                tryAssemble();
            }
        }
    }

    /** Search the neighborhood of this thruster for the largest valid
     *  same-facing cube that contains it. Disassembles any smaller sub-multis
     *  found inside before forming. */
    protected void tryAssemble() {
        Direction facing = getBlockState().getValue(AbstractThrusterBlock.FACING);
        for (int size = MAX_WIDTH; size >= 2; size--) {
            BlockPos origin = findCubeOrigin(size, facing);
            if (origin != null) {
                formMulti(origin, size, facing);
                return;
            }
        }
    }

    /** Returns the lowest-corner position of a valid size^3 cube that
     *  contains this BE, or null if none exists. */
    @Nullable
    protected BlockPos findCubeOrigin(int size, Direction facing) {
        for (int dx = 0; dx < size; dx++) {
            for (int dy = 0; dy < size; dy++) {
                for (int dz = 0; dz < size; dz++) {
                    BlockPos origin = worldPosition.offset(-dx, -dy, -dz);
                    if (isValidCube(origin, size, facing)) return origin;
                }
            }
        }
        return null;
    }

    /** A cube is valid if every position is a thruster, every thruster faces
     *  `facing`, and no position is already part of a DIFFERENT multi of
     *  size >= size (we can't steal cells from a multi that's already at
     *  least as big as the one we want to form). Sub-multis strictly smaller
     *  will be disassembled and absorbed. */
    protected boolean isValidCube(BlockPos origin, int size, Direction facing) {
        for (int x = 0; x < size; x++) {
            for (int y = 0; y < size; y++) {
                for (int z = 0; z < size; z++) {
                    BlockPos pos = origin.offset(x, y, z);
                    BlockState state = level.getBlockState(pos);
                    if (!state.hasProperty(AbstractThrusterBlock.FACING)) return false;
                    if (state.getValue(AbstractThrusterBlock.FACING) != facing) return false;
                    BlockEntity be = level.getBlockEntity(pos);
                    if (!(be instanceof ThrusterBlockEntity t)) return false;
                    if (t.isMultiblock() && t.width >= size) return false;
                }
            }
        }
        return true;
    }

    /** Walk the cube, break any sub-multis, gather every member's fluids into
     *  the controller's scaled aggregate tanks, and update multi state on all
     *  cells. */
    protected void formMulti(BlockPos origin, int size, Direction facing) {
        // Pass 1: dissolve any sub-multis so their fluids are redistributed to
        // individual cells first (avoids fluid loss when shrinking controller
        // capacities mid-form).
        for (int x = 0; x < size; x++) {
            for (int y = 0; y < size; y++) {
                for (int z = 0; z < size; z++) {
                    BlockPos pos = origin.offset(x, y, z);
                    BlockEntity be = level.getBlockEntity(pos);
                    if (be instanceof ThrusterBlockEntity t && t.isMultiblock()) {
                        ThrusterBlockEntity ctrl = t.getControllerBE();
                        if (ctrl != null) ctrl.disassembleMulti();
                    }
                }
            }
        }

        // Pass 2: gather
        List<ThrusterBlockEntity> members = new ArrayList<>(size * size * size);
        ThrusterBlockEntity controller = null;
        FluidStack totalFuel = FluidStack.EMPTY;
        FluidStack totalOx = FluidStack.EMPTY;
        for (int x = 0; x < size; x++) {
            for (int y = 0; y < size; y++) {
                for (int z = 0; z < size; z++) {
                    BlockPos pos = origin.offset(x, y, z);
                    BlockEntity be = level.getBlockEntity(pos);
                    if (!(be instanceof ThrusterBlockEntity t)) return; // abort: race condition
                    members.add(t);
                    if (pos.equals(origin)) controller = t;
                    totalFuel = mergeFluid(totalFuel, t.tank.getPrimaryHandler().getFluid());
                    totalOx = mergeFluid(totalOx, t.oxidizerTank.getPrimaryHandler().getFluid());
                    t.tank.getPrimaryHandler().setFluid(FluidStack.EMPTY);
                    t.oxidizerTank.getPrimaryHandler().setFluid(FluidStack.EMPTY);
                }
            }
        }
        if (controller == null) return;

        // Pass 3: scale controller capacity and set its aggregate contents
        int newCap = BASE_CAPACITY * size * size * size;
        controller.tank.getPrimaryHandler().setCapacity(newCap);
        controller.oxidizerTank.getPrimaryHandler().setCapacity(newCap);
        controller.tank.getPrimaryHandler().setFluid(trimToCapacity(totalFuel, newCap));
        controller.oxidizerTank.getPrimaryHandler().setFluid(trimToCapacity(totalOx, newCap));

        // Pass 4: stamp multi state on everyone
        for (ThrusterBlockEntity t : members) {
            t.controllerPos = (t == controller) ? null : origin;
            t.width = size;
            t.invalidateMultiCap();
            t.isThrustDirty = true;
            // Flip the cosmetic MULTIBLOCK blockstate property so the cell's
            // per-block model swaps to the empty model. The controller's BER
            // draws the purpose-made multiblock model in place of the now-
            // invisible cells. Using UPDATE_CLIENTS only -- no neighbor or
            // block updates -- because this change is purely visual and we
            // don't want to re-trigger redstone checks for every cell on the
            // same tick we're already mid-assembly. We read the LIVE world
            // state via level.getBlockState(pos) rather than the BE's
            // cached getBlockState() so this is robust against any caller
            // that might invoke formMulti while the world is mid-
            // transition (same defensive pattern as disassembleMulti).
            BlockPos cellPos = t.getBlockPos();
            BlockState liveState = level.getBlockState(cellPos);
            if (liveState.getBlock() instanceof ThrusterBlock
                    && liveState.hasProperty(ThrusterBlock.MULTIBLOCK)
                    && !liveState.getValue(ThrusterBlock.MULTIBLOCK)) {
                level.setBlock(cellPos,
                        liveState.setValue(ThrusterBlock.MULTIBLOCK, true),
                        Block.UPDATE_CLIENTS);
            }
            t.setChanged();
            t.notifyUpdate();
        }

        // Pass 5: run an obstruction scan NOW so the new nozzle-face cells
        // have correct emptyBlocks before the next tick / neighbor change.
        // This ensures particle emission and thrust are right immediately
        // after assembly.
        controller.calculateObstruction(level, origin, facing);
    }

    /** Cooperative hook for block relocators: call this on any member of a
     *  formed multi right before the block is saved-and-moved, and the
     *  cube's next disassembleMulti() call will be skipped. Sets a flag on
     *  whichever cell you call it on; the flag is most useful on the
     *  controller (since that's what actually runs disassemble), but
     *  calling it on a slave is harmless and makes the caller simpler --
     *  the assembler iterates every cell and sets the flag on each. */
    public void preventNextDisassembly() {
        this.suppressNextDisassembly = true;
    }

    /** Distribute controller's aggregate fluid evenly back to all members
     *  (each member can hold exactly BASE_CAPACITY, and the aggregate was at
     *  most size^3 * BASE_CAPACITY -- so the distribution is lossless).
     *
     *  When {@link #preventNextDisassembly()} has been called, the next
     *  invocation of this method will be skipped and the flag cleared. This
     *  cooperative hook exists so the PhysicsAssembler (and any future
     *  relocation tool) can move an intact cube block-by-block without the
     *  multi dissolving mid-move. */
    public void disassembleMulti() {
        if (!isController() || !isMultiblock()) return;
        if (suppressNextDisassembly) {
            suppressNextDisassembly = false;
            return;
        }
        int size = width;
        BlockPos origin = worldPosition;

        FluidStack fuelPool = tank.getPrimaryHandler().getFluid().copy();
        FluidStack oxPool = oxidizerTank.getPrimaryHandler().getFluid().copy();
        tank.getPrimaryHandler().setFluid(FluidStack.EMPTY);
        oxidizerTank.getPrimaryHandler().setFluid(FluidStack.EMPTY);
        tank.getPrimaryHandler().setCapacity(BASE_CAPACITY);
        oxidizerTank.getPrimaryHandler().setCapacity(BASE_CAPACITY);

        for (int x = 0; x < size; x++) {
            for (int y = 0; y < size; y++) {
                for (int z = 0; z < size; z++) {
                    BlockPos pos = origin.offset(x, y, z);
                    BlockEntity be = level.getBlockEntity(pos);
                    if (!(be instanceof ThrusterBlockEntity t)) continue;
                    // Share: each member takes up to BASE_CAPACITY from each pool.
                    if (!fuelPool.isEmpty()) {
                        int take = Math.min(BASE_CAPACITY, fuelPool.getAmount());
                        FluidStack slice = new FluidStack(fuelPool, take);
                        t.tank.getPrimaryHandler().fill(slice, FluidAction.EXECUTE);
                        fuelPool.shrink(take);
                    }
                    if (!oxPool.isEmpty()) {
                        int take = Math.min(BASE_CAPACITY, oxPool.getAmount());
                        FluidStack slice = new FluidStack(oxPool, take);
                        t.oxidizerTank.getPrimaryHandler().fill(slice, FluidAction.EXECUTE);
                        oxPool.shrink(take);
                    }
                    t.controllerPos = null;
                    t.width = 1;
                    // Let every released cell re-attempt assembly next tick,
                    // so removing one block from a 3x3x3 can still settle into
                    // a 2x2x2 with the remaining corner cells.
                    t.updateConnectivity = true;
                    t.invalidateMultiCap();
                    t.isThrustDirty = true;
                    // Zero out thrust so slaves don't keep applying force
                    // after the multi breaks.
                    t.thrusterData.setThrust(0);
                    // Flip the cosmetic MULTIBLOCK blockstate property back
                    // off so each cell's single-block model returns. See
                    // formMulti for the symmetrical flip-on.
                    //
                    // CRITICAL: disassembleMulti runs from onRemove when a
                    // player breaks one of the cells. At that moment, the
                    // broken cell's world state may already have been
                    // swapped to air mid-transition. We must read the LIVE
                    // world state via level.getBlockState(pos) -- NOT the
                    // BE's cached t.getBlockState() -- and only setBlock if
                    // it is still our thruster. Otherwise we would re-place
                    // a thruster block on top of the one the player just
                    // broke, making the block effectively unbreakable (each
                    // break triggers a re-place through this path).
                    BlockState liveState = level.getBlockState(pos);
                    if (liveState.getBlock() instanceof ThrusterBlock
                            && liveState.hasProperty(ThrusterBlock.MULTIBLOCK)
                            && liveState.getValue(ThrusterBlock.MULTIBLOCK)) {
                        level.setBlock(pos,
                                liveState.setValue(ThrusterBlock.MULTIBLOCK, false),
                                Block.UPDATE_CLIENTS);
                    }
                    t.setChanged();
                    t.notifyUpdate();
                }
            }
        }
    }

    private static FluidStack mergeFluid(FluidStack pool, FluidStack addition) {
        if (addition.isEmpty()) return pool;
        if (pool.isEmpty()) return addition.copy();
        if (pool.isFluidEqual(addition)) {
            FluidStack out = pool.copy();
            out.grow(addition.getAmount());
            return out;
        }
        // Mismatched fuel types: keep the pool's fluid, discard the extra.
        return pool;
    }

    private static FluidStack trimToCapacity(FluidStack stack, int cap) {
        if (stack.isEmpty() || stack.getAmount() <= cap) return stack;
        FluidStack out = stack.copy();
        out.setAmount(cap);
        return out;
    }

    // =====================================================================
    // Thrust logic
    // =====================================================================

    @Override
    public void updateThrust(BlockState currentBlockState) {
        // Slaves never drive their own thrust -- the controller writes to
        // their thrusterData each tick.
        if (!isController()) {
            isThrustDirty = false;
            return;
        }
        if (isMultiblock()) {
            updateMultiThrust(currentBlockState);
        } else {
            updateSingleThrust(currentBlockState);
        }
    }

    /** Single-block path -- preserves the exact pre-multiblock behavior so
     *  fuel-only thrusters in existing worlds are untouched. */
    protected void updateSingleThrust(BlockState currentBlockState) {
        float thrust = 0;
        float currentPower = getPower();
        if (isWorking() && currentPower > 0) {
            FluidThrusterProperties properties = getFuelProperties(fluidStack().getRawFluid());
            float obstructionEffect = calculateObstructionEffect();
            float thrustPercentage = Math.min(currentPower, obstructionEffect);
            if (thrustPercentage > 0 && properties != null) {
                int tickRate = PropulsionConfig.THRUSTER_TICKS_PER_UPDATE.get();
                int consumption = calculateFuelConsumption(currentPower, properties.consumptionMultiplier, tickRate);
                FluidStack drained = tank.getPrimaryHandler().drain(consumption, FluidAction.EXECUTE);
                int consumed = drained.getAmount();
                if (consumed > 0) {
                    float ratio = (float) consumed / (float) consumption;
                    float thrustMultiplier = PropulsionConfig.THRUSTER_THRUST_MULTIPLIER.get().floatValue();
                    thrust = BASE_MAX_THRUST * thrustMultiplier * thrustPercentage * properties.thrustMultiplier * ratio;
                }
            }
        }
        thrusterData.setThrust(thrust);
        isThrustDirty = false;
    }

    /** Multiblock path: scale consumption and thrust by block count, require
     *  both fuel and oxidizer, distribute the resulting thrust equally across
     *  every cube member so the net force passes through the geometric
     *  center (no parasitic torque). */
    protected void updateMultiThrust(BlockState currentBlockState) {
        int n = width * width * width;
        float totalThrust = 0;
        float currentPower = getPower();

        if (isWorking() && currentPower > 0) {
            FluidThrusterProperties properties = getFuelProperties(fluidStack().getRawFluid());
            float obstructionEffect = calculateObstructionEffect();
            float thrustPercentage = Math.min(currentPower, obstructionEffect);
            if (thrustPercentage > 0 && properties != null) {
                int tickRate = PropulsionConfig.THRUSTER_TICKS_PER_UPDATE.get();
                int baseConsumption = calculateFuelConsumption(currentPower, properties.consumptionMultiplier, tickRate);
                float fuelEff = getMultiblockFuelEfficiency(width);
                float oxEff = getMultiblockOxidizerEfficiency(width);
                int fuelNeeded = (int) Math.ceil(baseConsumption * (double) n * fuelEff);
                int oxNeeded = (int) Math.ceil(baseConsumption * (double) n * oxEff);

                // Simulate drains first so a shortfall in either fluid doesn't
                // waste the other.
                FluidStack fuelSim = tank.getPrimaryHandler().drain(fuelNeeded, FluidAction.SIMULATE);
                FluidStack oxSim = oxidizerTank.getPrimaryHandler().drain(oxNeeded, FluidAction.SIMULATE);
                int fuelAvail = fuelSim.getAmount();
                int oxAvail = oxSim.getAmount();

                // Scale by whichever is the limiting reagent.
                float fuelRatio = fuelNeeded > 0 ? (float) fuelAvail / (float) fuelNeeded : 0;
                float oxRatio = oxNeeded > 0 ? (float) oxAvail / (float) oxNeeded : 0;
                float limitRatio = Math.min(fuelRatio, oxRatio);

                if (limitRatio > 0) {
                    int fuelActual = (int) (fuelNeeded * limitRatio);
                    int oxActual = (int) (oxNeeded * limitRatio);
                    tank.getPrimaryHandler().drain(fuelActual, FluidAction.EXECUTE);
                    oxidizerTank.getPrimaryHandler().drain(oxActual, FluidAction.EXECUTE);
                    float thrustMultiplier = PropulsionConfig.THRUSTER_THRUST_MULTIPLIER.get().floatValue();
                    totalThrust = BASE_MAX_THRUST * thrustMultiplier * thrustPercentage
                            * properties.thrustMultiplier * limitRatio * n;
                }
            }
        }

        // Distribute across every cube member. Each ThrusterForceApplier reads
        // its own thrusterData, so net force = totalThrust applied at the
        // centroid of the cube -- no torque.
        float share = totalThrust / n;
        BlockPos origin = worldPosition;
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < width; y++) {
                for (int z = 0; z < width; z++) {
                    BlockEntity be = level.getBlockEntity(origin.offset(x, y, z));
                    if (be instanceof ThrusterBlockEntity t) {
                        t.thrusterData.setThrust(share);
                    }
                }
            }
        }
        isThrustDirty = false;
    }

    @Override
    protected boolean isWorking() {
        if (!isController()) {
            ThrusterBlockEntity ctrl = getControllerBE();
            return ctrl != null && ctrl.isWorking();
        }
        if (!validFluid()) return false;
        // Multiblock additionally requires oxidizer to be present.
        if (isMultiblock() && !validOxidizer()) return false;
        return true;
    }

    @Override
    protected boolean shouldEmitParticles() {
        if (!super.shouldEmitParticles()) return false;
        if (!isMultiblock()) return true;
        // Only cells on the nozzle face of the cube emit -- otherwise interior
        // and rear cells spray exhaust through other cube cells.
        if (!isOnNozzleFace()) return false;
        // A nozzle cell with zero empty space in front of it has nothing to
        // emit into. Its own emptyBlocks was set by the controller's cube
        // obstruction scan.
        return emptyBlocks > 0;
    }

    /** A member is "on the nozzle face" if the block immediately behind it
     *  (in FACING.getOpposite(), the exhaust direction) is outside the cube.
     *  For a 2x2x2 cube that's 4 cells; for 3x3x3 it's 9. Note that FACING
     *  is the thrust direction, so exhaust exits the cube on FACING's
     *  opposite side. */
    protected boolean isOnNozzleFace() {
        ThrusterBlockEntity ctrl = getControllerBE();
        if (ctrl == null) return true;
        Direction facing = getBlockState().getValue(AbstractThrusterBlock.FACING);
        BlockPos origin = ctrl.worldPosition;
        BlockPos exhaustExit = worldPosition.relative(facing.getOpposite());
        int s = ctrl.width;
        int dx = exhaustExit.getX() - origin.getX();
        int dy = exhaustExit.getY() - origin.getY();
        int dz = exhaustExit.getZ() - origin.getZ();
        boolean inside = dx >= 0 && dx < s && dy >= 0 && dy < s && dz >= 0 && dz < s;
        return !inside;
    }

    // Pulling a multiblock's per-cell plumes a fixed few pixels toward the
    // cube's axis tightens up the visual grouping:
    //   * 2x2x2: the 4 corner plumes each pull inward diagonally, so they
    //     read as one clustered exhaust rather than four independent ones.
    //   * 3x3x3: the center cell's plume stays put (its vector to the axis
    //     is zero, short-circuited below); the 8 surrounding plumes -- 4
    //     edge-midpoints and 4 corners -- all pull inward by the same fixed
    //     magnitude, so the outer ring visibly closes in on the center.
    // 2 pixels (2/16 of a block) is subtle enough to read as polish rather
    // than a geometry change, while still being noticeable side-by-side
    // with the pre-change layout.
    private static final double MULTIBLOCK_PARTICLE_INWARD_OFFSET = 4.0 / 16.0;

    /** Perpendicular nudge of each multiblock cell's plume toward the cube
     *  axis. Returns (0,0,0) for single thrusters and for the geometric
     *  center cell of an odd-width cube (the 3x3x3's middle), leaving
     *  those visuals unchanged. For every other multiblock cell, returns a
     *  vector of length {@link #MULTIBLOCK_PARTICLE_INWARD_OFFSET} aimed
     *  at the cube's central axis -- same magnitude for edges and corners,
     *  so a 3x3x3's outer ring tightens uniformly. The component along the
     *  nozzle axis is zeroed: the offset only moves particles sideways, not
     *  forward or back, so they still exit the nozzle plane correctly. */
    @Override
    protected Vector3d getExtraParticleOriginOffset(Direction oppositeDirection) {
        if (!isMultiblock()) return super.getExtraParticleOriginOffset(oppositeDirection);
        ThrusterBlockEntity ctrl = getControllerBE();
        if (ctrl == null) return super.getExtraParticleOriginOffset(oppositeDirection);
        int size = ctrl.width;
        if (size <= 1) return super.getExtraParticleOriginOffset(oppositeDirection);

        // Cell's position within the cube in local coords (0..size-1 per axis).
        BlockPos origin = ctrl.worldPosition;
        double lx = worldPosition.getX() - origin.getX();
        double ly = worldPosition.getY() - origin.getY();
        double lz = worldPosition.getZ() - origin.getZ();

        // Vector from this cell toward the cube's geometric center.
        double half = (size - 1) * 0.5;
        double tx = half - lx;
        double ty = half - ly;
        double tz = half - lz;

        // Zero out the axial component. Only lateral nudging -- we don't
        // want particles to spawn forward (past the nozzle face) or
        // rearward (inside the next cell behind) relative to their usual
        // spawn point. oppositeDirection is the exhaust axis, which shares
        // axis with FACING.
        switch (oppositeDirection.getAxis()) {
            case X: tx = 0; break;
            case Y: ty = 0; break;
            case Z: tz = 0; break;
        }

        // Normalize to a fixed inward magnitude. The 3x3x3's center cell
        // lands here with len == 0 -- return zero so its plume is left
        // exactly where it was, matching the requirement that only the
        // 8 surrounding emitters shift.
        double len = Math.sqrt(tx * tx + ty * ty + tz * tz);
        if (len < 1e-6) return super.getExtraParticleOriginOffset(oppositeDirection);

        double scale = MULTIBLOCK_PARTICLE_INWARD_OFFSET / len;
        return new Vector3d(tx * scale, ty * scale, tz * scale);
    }

    // =====================================================================
    // Obstruction (cube-wide) and redstone power aggregation
    // =====================================================================
    //
    // Goals:
    //  * A multiblock's obstruction is computed as a whole -- only the
    //    controller drives the scan, slaves contribute nothing on their own.
    //  * Each nozzle-face cell gets its own empty-blocks count (needed for
    //    per-cell particle gating), but the value that feeds thrust is the
    //    AVERAGE across nozzle-face cells, so thrust scales proportionally
    //    to how many nozzles are clear.
    //  * Redstone input aggregates across the whole cube: the effective
    //    power is MAX(localSignal) over all members. Any wire touching any
    //    cell is enough to fire the whole cube.

    /** Overridden so (a) slaves never scan on their own, and (b) the
     *  controller fans a scan across every nozzle-face cell in the cube.
     *  Called from the parent's periodic tick and from doRedstoneCheck. */
    @Override
    public void calculateObstruction(Level lvl, BlockPos pos, Direction forwardDirection) {
        if (!isController() && isMultiblock()) {
            // Slave: do nothing. The controller owns obstruction state.
            return;
        }
        if (isController() && isMultiblock()) {
            runCubeObstructionScan(lvl);
            return;
        }
        // Single-block: unchanged.
        super.calculateObstruction(lvl, pos, forwardDirection);
    }

    /** Calls the parent's scan algorithm directly, bypassing this class's
     *  override. The controller uses this to populate each nozzle-face
     *  slave's own `emptyBlocks` field. */
    void runObstructionScan(Level lvl, BlockPos pos, Direction forwardDirection) {
        super.calculateObstruction(lvl, pos, forwardDirection);
    }

    /** Controller entry point: scans every nozzle-face cell in the cube,
     *  writes each slave's own emptyBlocks, and if any changed, re-sends the
     *  affected cells to clients so their particles react to the obstruction
     *  in real time. */
    private void runCubeObstructionScan(Level lvl) {
        Direction facing = getBlockState().getValue(AbstractThrusterBlock.FACING);
        BlockPos origin = worldPosition;
        boolean anyChanged = false;
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < width; y++) {
                for (int z = 0; z < width; z++) {
                    BlockPos cellPos = origin.offset(x, y, z);
                    BlockEntity be = lvl.getBlockEntity(cellPos);
                    if (!(be instanceof ThrusterBlockEntity t)) continue;
                    if (!t.isOnNozzleFace()) continue;
                    int prev = t.emptyBlocks;
                    t.runObstructionScan(lvl, cellPos, facing);
                    if (t.emptyBlocks != prev) {
                        anyChanged = true;
                        t.setChanged();
                        BlockState bs = t.getBlockState();
                        lvl.sendBlockUpdated(cellPos, bs, bs, Block.UPDATE_CLIENTS);
                    }
                }
            }
        }
        if (anyChanged) {
            isThrustDirty = true;
        }
    }

    /** The effect returned by this method feeds into the thrust math. For
     *  multiblocks, we average the nozzle-face cells' effects -- if half the
     *  nozzles are blocked, the cube produces ~half its rated thrust.
     *  Slaves proxy upward so any caller reading the effect gets the
     *  aggregate regardless of which cell they asked. */
    @Override
    protected float calculateObstructionEffect() {
        if (!isController() && isMultiblock()) {
            ThrusterBlockEntity ctrl = getControllerBE();
            return ctrl != null ? ctrl.calculateObstructionEffect() : 0f;
        }
        if (isController() && isMultiblock() && level != null) {
            int sum = 0;
            int count = 0;
            BlockPos origin = worldPosition;
            for (int x = 0; x < width; x++) {
                for (int y = 0; y < width; y++) {
                    for (int z = 0; z < width; z++) {
                        BlockEntity be = level.getBlockEntity(origin.offset(x, y, z));
                        if (be instanceof ThrusterBlockEntity t && t.isOnNozzleFace()) {
                            sum += t.emptyBlocks;
                            count++;
                        }
                    }
                }
            }
            if (count == 0) return 0f;
            return (float) sum / ((float) count * (float) OBSTRUCTION_LENGTH);
        }
        return super.calculateObstructionEffect();
    }

    /** Goggles and any external readers get the aggregate value when
     *  querying any cell of a multiblock. */
    @Override
    public int getEmptyBlocks() {
        if (!isController() && isMultiblock()) {
            ThrusterBlockEntity ctrl = getControllerBE();
            return ctrl != null ? ctrl.getEmptyBlocks() : 0;
        }
        if (isController() && isMultiblock() && level != null) {
            int sum = 0;
            int count = 0;
            BlockPos origin = worldPosition;
            for (int x = 0; x < width; x++) {
                for (int y = 0; y < width; y++) {
                    for (int z = 0; z < width; z++) {
                        BlockEntity be = level.getBlockEntity(origin.offset(x, y, z));
                        if (be instanceof ThrusterBlockEntity t && t.isOnNozzleFace()) {
                            sum += t.emptyBlocks;
                            count++;
                        }
                    }
                }
            }
            return count > 0 ? sum / count : OBSTRUCTION_LENGTH;
        }
        return super.getEmptyBlocks();
    }

    /** Each cell still stores its own local redstone reading, but in a
     *  multiblock any change additionally funnels a "thrust dirty" pulse to
     *  the controller so the aggregate MAX is re-evaluated on the next
     *  tick. The cell itself still sends its own update so clients see the
     *  new value (clients compute the same aggregate locally for particle
     *  rendering). */
    @Override
    public void setRedstoneInput(int power) {
        if (this.redstoneInput == power) return;
        this.redstoneInput = power;
        if (controlMode == ControlMode.NORMAL) {
            dirtyThrust();
            notifyUpdate();
        }
        if (isMultiblock() && !isController()) {
            ThrusterBlockEntity ctrl = getControllerBE();
            if (ctrl != null) {
                ctrl.dirtyThrust();
                ctrl.notifyUpdate();
            }
        }
    }

    /** For multiblocks, power is the MAX of every cell's local redstone
     *  signal (so a wire touching any face of the cube activates the whole
     *  thing). Slaves proxy through the controller; singles keep the
     *  original behavior. Peripheral/digital control is not aggregated --
     *  only the controller's digital input is consulted. */
    @Override
    public float getPower() {
        if (!isController() && isMultiblock()) {
            ThrusterBlockEntity ctrl = getControllerBE();
            return ctrl != null ? ctrl.getPower() : 0f;
        }
        if (controlMode == ControlMode.PERIPHERAL) {
            return digitalInput;
        }
        if (isController() && isMultiblock()) {
            return getAggregatedRedstone() / 15.0f;
        }
        return redstoneInput / 15.0f;
    }

    /** Controller-side helper: MAX across every cube member's localRedstone.
     *  Iterated fresh on each getPower() call -- bounded to 27 BE lookups
     *  for a 3x3x3, which is negligible. */
    private int getAggregatedRedstone() {
        int max = redstoneInput;
        if (level == null) return max;
        BlockPos origin = worldPosition;
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < width; y++) {
                for (int z = 0; z < width; z++) {
                    if (x == 0 && y == 0 && z == 0) continue; // self already in max
                    BlockEntity be = level.getBlockEntity(origin.offset(x, y, z));
                    if (be instanceof ThrusterBlockEntity t) {
                        if (t.redstoneInput > max) max = t.redstoneInput;
                    }
                }
            }
        }
        return max;
    }

    // =====================================================================
    // Capability routing
    // =====================================================================

    @Override
    public <T> LazyOptional<T> getCapability(Capability<T> cap, @Nullable Direction side) {
        if (cap == ForgeCapabilities.FLUID_HANDLER) {
            if (isMultiblock()) {
                ThrusterBlockEntity ctrl = getControllerBE();
                if (ctrl == null) return LazyOptional.empty();
                Direction cubeFacing = ctrl.getBlockState().getValue(AbstractThrusterBlock.FACING);
                // side == null is a non-directional query (goggles, JEI
                // previews, things that just want to read contents). Hand out
                // the combined wrapper so those still work on ANY cell of the
                // cube -- the front-layer gating below is strictly for
                // directional (pipe) queries, and must not break the ability
                // to inspect a multi by looking at any of its cells.
                if (side == null) return ctrl.getMultiFluidCapability().cast();
                // Pipe inputs are restricted to the cube's front layer: the
                // slice of cells on the FACING side of the cube (the intake
                // side, opposite the nozzle). For a 3x3x3 that's 9 cells, for
                // a 2x2x2 that's 4. Cells behind the front layer (interior
                // cells and nozzle-side cells) expose no fluid capability on
                // any face, so Create pipes cannot attach fuel or oxidizer
                // lines to them. Front-layer cells still route fuel vs.
                // oxidizer via the same side-to-tank mapping used previously,
                // meaning all non-nozzle-pointing external faces of those
                // front cells accept pipes.
                if (!isFrontLayerCell(ctrl, cubeFacing)) return LazyOptional.empty();
                // The nozzle face never accepts fluids -- that's where the
                // exhaust exits. FACING is the thrust direction; exhaust
                // comes out the OPPOSITE side. For a front-layer cell this
                // side always points inward at the neighbor cell behind it,
                // so in practice a pipe can't physically connect here
                // anyway, but we keep the guard for clarity and defense.
                if (side == cubeFacing.getOpposite()) return LazyOptional.empty();
                // Oxidizer lanes: Top/Bottom for horizontal thrusters, or the
                // Z pair (north/south) for vertical-facing thrusters.
                if (isOxidizerFace(side, cubeFacing)) {
                    return ctrl.oxidizerTank.getCapability().cast();
                }
                // Fuel lanes: Left, Right, and Front (the side opposite the
                // nozzle -- which is the FACING side).
                if (isFuelFace(side, cubeFacing)) {
                    return ctrl.tank.getCapability().cast();
                }
                return LazyOptional.empty();
            }
            // Single-block behavior: only fuel is accepted. The oxidizer
            // capability is intentionally NOT exposed here, even though
            // the behaviour is still attached to the BE -- singles do not
            // consume oxidizer and exposing the cap would let pipes fill
            // a tank whose contents the thruster never reads. Any leftover
            // oxidizer from a prior disassembleMulti() stays dormant in
            // the private tank until the cell re-forms into a cube, at
            // which point formMulti() harvests it back into the aggregate.
            if (side == getFluidCapSide() || side == null) return tank.getCapability().cast();
        }
        if (PropulsionCompatibility.CC_ACTIVE && computerBehaviour.isPeripheralCap(cap)) {
            return computerBehaviour.getPeripheralCapability().cast();
        }
        return super.getCapability(cap, side);
    }

    /** True iff this cell is in the cube's front layer -- i.e. the slice
     *  of cells at the FACING extreme of the cube. For a cube facing NORTH
     *  (-Z) the front layer is the Z=0 slice relative to the controller's
     *  origin; for a cube facing EAST (+X) it is the X=size-1 slice; and
     *  so on. The front layer is where Create pipes are allowed to push
     *  fluids in; all other cells (interior and nozzle-side) expose no
     *  fluid capability to directional queries.
     *
     *  Uses the live world position of THIS cell relative to the
     *  controller's origin rather than any cached cube-local index, so it
     *  is robust to the mod's own relocation path (PhysicsAssembler copies
     *  BEs to new shipyard coordinates while keeping the multi intact --
     *  the origin moves with them, so the relative test still holds). */
    private boolean isFrontLayerCell(ThrusterBlockEntity ctrl, Direction cubeFacing) {
        if (ctrl == null) return false;
        BlockPos origin = ctrl.getController();
        int size = ctrl.width;
        int rel;
        switch (cubeFacing.getAxis()) {
            case X: rel = worldPosition.getX() - origin.getX(); break;
            case Y: rel = worldPosition.getY() - origin.getY(); break;
            case Z: rel = worldPosition.getZ() - origin.getZ(); break;
            default: return false;
        }
        // Origin is the lowest-corner of the cube, so relative coords run
        // 0..size-1. "Front" is the FACING extreme: size-1 when FACING
        // points along the positive axis direction, 0 when negative.
        int frontIdx = (cubeFacing.getAxisDirection() == Direction.AxisDirection.POSITIVE)
                ? size - 1
                : 0;
        return rel == frontIdx;
    }

    /** True iff `side` is one of the two faces designated for oxidizer inflow
     *  on a multiblock with the given nozzle direction. Horizontal thrusters
     *  use Y (UP/DOWN); vertical-facing thrusters (FACING == UP or DOWN) fall
     *  back to Z (NORTH/SOUTH) so there's always a well-defined pair. */
    private static boolean isOxidizerFace(Direction side, Direction facing) {
        if (side.getAxis() == facing.getAxis()) return false;
        if (facing.getAxis() == Direction.Axis.Y) {
            return side.getAxis() == Direction.Axis.Z;
        }
        return side.getAxis() == Direction.Axis.Y;
    }

    /** Fuel goes in through any non-nozzle, non-oxidizer face -- i.e. Left,
     *  Right, and Front. "Front" in the user's convention is the side
     *  opposite the nozzle, which in our geometry is the FACING side
     *  (since FACING is the thrust direction and the exhaust exits
     *  FACING.getOpposite()). */
    private static boolean isFuelFace(Direction side, Direction facing) {
        if (side == facing.getOpposite()) return false; // nozzle
        return !isOxidizerFace(side, facing);
    }

    /** Accepts the mod's own oxidizer directly AND any fluid in the
     *  forge:oxidizer tag. The direct comparison is important because tag
     *  lookups return false if the tag data file is missing -- without it,
     *  inserting oxidizer would silently fail. */
    private boolean isOxidizer(FluidStack stack) {
        if (stack.isEmpty()) return false;
        return stack.getFluid().isSame(PropulsionFluids.OXIDIZER.get())
                || stack.getFluid().is(OXIDIZER_TAG);
    }

    protected LazyOptional<IFluidHandler> getMultiFluidCapability() {
        if (multiFluidCap == null || !multiFluidCap.isPresent()) {
            multiFluidCap = LazyOptional.of(() -> new CombinedTankWrapper(
                    tank.getCapability().orElse(null),
                    oxidizerTank.getCapability().orElse(null)
            ).enforceVariety());
        }
        return multiFluidCap;
    }

    protected void invalidateMultiCap() {
        if (multiFluidCap != null) {
            multiFluidCap.invalidate();
            multiFluidCap = null;
        }
    }

    @Override
    public void invalidate() {
        super.invalidate();
        invalidateMultiCap();
    }

    protected Direction getFluidCapSide() {
        return getBlockState().getValue(ThrusterBlock.FACING);
    }

    // getOxidizerCapSide() used to designate the single-block oxidizer
    // input face, but singles no longer accept oxidizer (see getCapability)
    // so the helper is gone. Multiblocks route oxidizer via isOxidizerFace
    // instead.

    /** Per-block fuel consumption multiplier for a multiblock of the given
     *  cube width. Returns 1.0 for anything other than the supported cube
     *  sizes so future width values (or width==1 called by mistake) behave
     *  as a no-op instead of crashing on a missing config key. */
    private static float getMultiblockFuelEfficiency(int cubeWidth) {
        if (cubeWidth == 2) return PropulsionConfig.THRUSTER_MULTIBLOCK_2X2X2_FUEL_EFFICIENCY.get().floatValue();
        if (cubeWidth == 3) return PropulsionConfig.THRUSTER_MULTIBLOCK_3X3X3_FUEL_EFFICIENCY.get().floatValue();
        return 1.0f;
    }

    /** Per-block oxidizer consumption multiplier for a multiblock of the
     *  given cube width. Applied to the same baseline as fuel (i.e., the
     *  single's fuel draw), not to actual fuel consumed -- this decouples
     *  oxidizer efficiency from fuel efficiency so 3x3x3 can be cheaper in
     *  both reagents without compound math, and so a shortfall of one
     *  doesn't retroactively change the demand for the other. */
    private static float getMultiblockOxidizerEfficiency(int cubeWidth) {
        if (cubeWidth == 2) return PropulsionConfig.THRUSTER_MULTIBLOCK_2X2X2_OXIDIZER_EFFICIENCY.get().floatValue();
        if (cubeWidth == 3) return PropulsionConfig.THRUSTER_MULTIBLOCK_3X3X3_OXIDIZER_EFFICIENCY.get().floatValue();
        return 1.0f;
    }


    // =====================================================================
    // NBT
    // =====================================================================

    @Override
    protected void write(CompoundTag compound, boolean clientPacket) {
        super.write(compound, clientPacket);
        compound.putInt("Width", width);
        if (controllerPos != null) {
            // Store the controller as a RELATIVE offset from this cell,
            // not an absolute world position. When the PhysicsAssembler
            // copies the whole cube into ship space block-by-block, every
            // cell moves by the same translation, so the offset is
            // preserved even though the absolute positions change. On
            // load we reconstruct the absolute controllerPos from the
            // BE's (already-updated) worldPosition plus this offset.
            compound.putInt("ControllerOffX", controllerPos.getX() - worldPosition.getX());
            compound.putInt("ControllerOffY", controllerPos.getY() - worldPosition.getY());
            compound.putInt("ControllerOffZ", controllerPos.getZ() - worldPosition.getZ());
        }
        if (updateConnectivity) {
            compound.putBoolean("UpdateConnectivity", true);
        }
    }

    @Override
    protected void read(CompoundTag compound, boolean clientPacket) {
        super.read(compound, clientPacket);
        width = Math.max(1, compound.getInt("Width"));
        if (compound.contains("ControllerOffX")) {
            // Current format: relative offset, survives block moves.
            controllerPos = worldPosition.offset(
                    compound.getInt("ControllerOffX"),
                    compound.getInt("ControllerOffY"),
                    compound.getInt("ControllerOffZ"));
        } else if (compound.contains("Controller")) {
            // Legacy format: absolute position. Accepted on read so any
            // pre-change saves don't lose their assembly, but we always
            // write the new format back out. This path will break under
            // a PhysicsAssembler move -- but only for multis that were
            // formed before the update and never re-saved since.
            controllerPos = NbtUtils.readBlockPos(compound.getCompound("Controller"));
        } else {
            controllerPos = null;
        }
        updateConnectivity = compound.getBoolean("UpdateConnectivity");
        // Re-apply scaled tank capacities on the controller after load. The
        // fluid content is preserved by SmartFluidTankBehaviour's own NBT.
        if (isController() && isMultiblock()) {
            int cap = BASE_CAPACITY * width * width * width;
            tank.getPrimaryHandler().setCapacity(cap);
            oxidizerTank.getPrimaryHandler().setCapacity(cap);
        }
    }

    // =====================================================================
    // Goggles + helpers
    // =====================================================================

    /**
     * Goggle entry point. Slaves redirect the whole tooltip call to the
     * controller: status line, obstruction readout, size, fluid tanks --
     * everything. Without this redirect, a player aiming at a rear or
     * interior cell would see that cell's stale private state (obstruction
     * never re-scans for non-nozzle cells, controlMode isn't the
     * controller's, etc.) instead of the cube's real status.
     *
     * <p>The controller runs the normal path and its override of
     * {@link #getGoggleStatus} and {@link #addThrusterDetails} pulls
     * aggregate values via {@link #getEmptyBlocks} and
     * {@link #calculateObstructionEffect}.
     */
    @Override
    public boolean addToGoggleTooltip(List<Component> tooltip, boolean isPlayerSneaking) {
        if (!isController() && isMultiblock()) {
            ThrusterBlockEntity ctrl = getControllerBE();
            if (ctrl != null && ctrl != this) {
                return ctrl.addToGoggleTooltip(tooltip, isPlayerSneaking);
            }
        }
        return super.addToGoggleTooltip(tooltip, isPlayerSneaking);
    }

    @Override
    protected LangBuilder getGoggleStatus() {
        if (!isController()) {
            ThrusterBlockEntity ctrl = getControllerBE();
            if (ctrl != null && ctrl != this) return ctrl.getGoggleStatus();
        }
        if (fluidStack().isEmpty()) {
            return CreateLang.builder().add(Component.translatable("createpropulsion.gui.goggles.thruster.status.no_fuel")).style(ChatFormatting.RED);
        } else if (!validFluid()) {
            return CreateLang.builder().add(Component.translatable("createpropulsion.gui.goggles.thruster.status.wrong_fuel")).style(ChatFormatting.RED);
        } else if (isMultiblock() && !validOxidizer()) {
            return CreateLang.builder().add(Component.translatable("createpropulsion.gui.goggles.thruster.status.no_oxidizer")).style(ChatFormatting.RED);
        } else if (!isPowered()) {
            return CreateLang.builder().add(Component.translatable("createpropulsion.gui.goggles.thruster.status.not_powered")).style(ChatFormatting.GOLD);
        } else if (getEmptyBlocks() == 0) {
            // getEmptyBlocks() (not the raw field) so multi controllers see
            // the aggregate across nozzle-face cells. See comment on
            // AbstractThrusterBlockEntity.addThrusterDetails for the same
            // reason.
            return CreateLang.builder().add(Component.translatable("createpropulsion.gui.goggles.thruster.obstructed")).style(ChatFormatting.RED);
        } else {
            return CreateLang.builder().add(Component.translatable("createpropulsion.gui.goggles.thruster.status.working")).style(ChatFormatting.GREEN);
        }
    }

    @Override
    protected void addThrusterDetails(List<Component> tooltip, boolean isPlayerSneaking) {
        super.addThrusterDetails(tooltip, isPlayerSneaking);
        // Slaves defer to the controller so players see real aggregate levels
        // whichever cell they're looking at.
        ThrusterBlockEntity ctrl = isController() ? this : getControllerBE();
        if (ctrl == null) ctrl = this;
        if (ctrl.isMultiblock()) {
            CreateLang.builder()
                    .add(Component.translatable("createpropulsion.gui.goggles.thruster.size"))
                    .text(": " + ctrl.width + "x" + ctrl.width + "x" + ctrl.width)
                    .style(ChatFormatting.GRAY)
                    .forGoggles(tooltip);
            // Bulk bonus line: shown only when the cube actually saves on
            // at least one reagent (efficiency < 1.0). Savings are
            // presented as negative percentages ("-20% fuel") to read as a
            // discount rather than an efficiency coefficient. Fuel-only
            // cubes (2x2x2 at defaults) show one fragment; cubes that
            // discount both (3x3x3 at defaults) show both fragments
            // separated by a comma. If someone tunes config so there is
            // no discount at any tier, the line is suppressed entirely.
            float fuelEff = getMultiblockFuelEfficiency(ctrl.width);
            float oxEff = getMultiblockOxidizerEfficiency(ctrl.width);
            int fuelSavePct = Math.round((1.0f - fuelEff) * 100.0f);
            int oxSavePct = Math.round((1.0f - oxEff) * 100.0f);
            if (fuelSavePct > 0 || oxSavePct > 0) {
                StringBuilder savings = new StringBuilder(": ");
                boolean first = true;
                if (fuelSavePct > 0) {
                    savings.append("-").append(fuelSavePct).append("% fuel");
                    first = false;
                }
                if (oxSavePct > 0) {
                    if (!first) savings.append(", ");
                    savings.append("-").append(oxSavePct).append("% oxidizer");
                }
                CreateLang.builder()
                        .add(Component.translatable("createpropulsion.gui.goggles.thruster.bulk_bonus"))
                        .text(savings.toString())
                        .style(ChatFormatting.DARK_AQUA)
                        .forGoggles(tooltip);
            }
        }
        containedFluidTooltip(tooltip, isPlayerSneaking, ctrl.tank.getCapability().cast());
        // Oxidizer is a multiblock-only concept -- singles don't consume it
        // and (as of this revision) can't accept it via pipes either, so
        // hiding the tooltip line keeps the goggle view in sync with the
        // actual behavior. A lone thruster that still has leftover oxidizer
        // in its tank from a prior disassembly will get it back when it
        // re-forms into a cube; until then, the fluid sits idle and the
        // tooltip just omits it.
        if (ctrl.isMultiblock()) {
            containedFluidTooltip(tooltip, isPlayerSneaking, ctrl.oxidizerTank.getCapability().cast());
        }
    }

    public FluidStack fluidStack() {
        ThrusterBlockEntity ctrl = isController() ? this : getControllerBE();
        if (ctrl == null) ctrl = this;
        return ctrl.tank.getPrimaryHandler().getFluid();
    }

    public FluidStack oxidizerStack() {
        ThrusterBlockEntity ctrl = isController() ? this : getControllerBE();
        if (ctrl == null) ctrl = this;
        return ctrl.oxidizerTank.getPrimaryHandler().getFluid();
    }

    public boolean validFluid() {
        if (fluidStack().isEmpty()) return false;
        return getFuelProperties(fluidStack().getRawFluid()) != null;
    }

    public boolean validOxidizer() {
        return isOxidizer(oxidizerStack());
    }

    public FluidThrusterProperties getFuelProperties(net.minecraft.world.level.material.Fluid fluid) {
        return ThrusterFuelManager.getProperties(fluid);
    }

    private int calculateFuelConsumption(float powerPercentage, float fluidPropertiesConsumptionMultiplier, int tick_rate) {
        float base_consumption = BASE_FUEL_CONSUMPTION * PropulsionConfig.THRUSTER_CONSUMPTION_MULTIPLIER.get().floatValue();
        return (int) Math.ceil(base_consumption * powerPercentage * fluidPropertiesConsumptionMultiplier * tick_rate);
    }

    @Override
    protected double getNozzleOffsetFromCenter() {
        return 0.95;
    }

    // =====================================================================
    // IMultiBlockEntityContainer.Fluid -- minimal impl for Create tooling.
    // Our own tryAssemble does the work; these methods let Create's generic
    // handlers inspect us without special-casing.
    // =====================================================================

    @Override
    public BlockPos getController() {
        return isController() ? worldPosition : controllerPos;
    }

    @Override
    public void setController(BlockPos pos) {
        if (worldPosition.equals(pos)) {
            controllerPos = null;
        } else {
            controllerPos = pos;
        }
        setChanged();
        notifyUpdate();
    }

    @Override
    public void removeController(boolean keepContents) {
        if (isController() && isMultiblock()) disassembleMulti();
    }

    @Override
    public BlockPos getLastKnownPos() {
        return worldPosition;
    }

    @Override
    public void preventConnectivityUpdate() {
        updateConnectivity = false;
    }

    @Override
    public void notifyMultiUpdated() {
        setChanged();
        notifyUpdate();
    }

    @Override
    public Direction.Axis getMainConnectionAxis() {
        BlockState state = getBlockState();
        if (state.hasProperty(AbstractThrusterBlock.FACING)) {
            return state.getValue(AbstractThrusterBlock.FACING).getAxis();
        }
        return Direction.Axis.Y;
    }

    @Override
    public int getMaxLength(Direction.Axis axis, int w) {
        return w; // cubes only
    }

    @Override
    public int getMaxWidth() {
        return MAX_WIDTH;
    }

    @Override
    public int getHeight() {
        return width;
    }

    @Override
    public void setHeight(int h) {
        // Height is forced to equal width (cube). Ignore.
    }

    @Override
    public int getWidth() {
        return width;
    }

    @Override
    public void setWidth(int w) {
        this.width = w;
    }

    @Override
    public boolean hasTank() {
        return true;
    }

    @Override
    public int getTankSize(int t) {
        return BASE_CAPACITY;
    }

    @Override
    public void setTankSize(int t, int blocks) {
        int cap = BASE_CAPACITY * blocks;
        if (t == 0) tank.getPrimaryHandler().setCapacity(cap);
        else oxidizerTank.getPrimaryHandler().setCapacity(cap);
    }

    @Override
    public IFluidTank getTank(int t) {
        return t == 0 ? tank.getPrimaryHandler() : oxidizerTank.getPrimaryHandler();
    }

    @Override
    public FluidStack getFluid(int t) {
        return getTank(t).getFluid().copy();
    }
}