package com.deltasf.createpropulsion.compat.computercraft;

import com.deltasf.createpropulsion.thruster.AbstractThrusterBlockEntity;
import com.deltasf.createpropulsion.thruster.ion.IonThrusterBlockEntity;
import com.simibubi.create.compat.computercraft.implementation.peripherals.SyncedPeripheral;
import dan200.computercraft.api.lua.LuaFunction;
import dan200.computercraft.api.peripheral.IComputerAccess;
import dan200.computercraft.api.peripheral.IPeripheral;
import org.jetbrains.annotations.NotNull;

/**
 * ComputerCraft peripheral for the Ion Thruster.
 *
 * <p>Mirrors {@link CreativeThrusterPeripheral} for the standard control
 * surface ({@code getObstruction}, {@code setPower}, {@code getPower},
 * attach/detach mode switching) and substitutes energy-buffer accessors
 * ({@code getEnergyStored}, {@code getMaxEnergyStored}) for the fluid
 * passthrough that {@link ThrusterPeripheral} exposes. The fluid peripheral
 * cannot be reused directly because it is generic over
 * {@code ThrusterBlockEntity} and its Lua methods all dispatch through
 * {@code blockEntity.tank} / {@code fluidStack()} / {@code validFluid()},
 * none of which exist on an Ion Thruster.
 */
public class IonThrusterPeripheral extends SyncedPeripheral<IonThrusterBlockEntity> {

    public IonThrusterPeripheral(IonThrusterBlockEntity blockEntity) {
        super(blockEntity);
    }

    @Override
    public final String getType() {
        return "propulsion_ion_thruster";
    }

    // ---- Standard thruster control surface (parallels both other peripherals) ----

    @LuaFunction
    public final int getObstruction() {
        return blockEntity.getEmptyBlocks();
    }

    @LuaFunction(mainThread = true)
    public final void setPower(double power) {
        blockEntity.setDigitalInput((float) power);
    }

    @LuaFunction(mainThread = true)
    public final float getPower() {
        return blockEntity.getPower();
    }

    // ---- Energy buffer accessors (replace the fluid passthrough) ----

    /** Current stored energy in FE/RF. */
    @LuaFunction(mainThread = true)
    public final int getEnergyStored() {
        return blockEntity.getEnergyStored();
    }

    /** Capacity of the internal energy buffer in FE/RF. */
    @LuaFunction(mainThread = true)
    public final int getMaxEnergyStored() {
        return blockEntity.getMaxEnergyStored();
    }

    // ---- Boilerplate (matches both sibling peripherals) ----

    @Override
    public boolean equals(IPeripheral other) {
        if (this == other) return true;
        if (other instanceof IonThrusterPeripheral otherThruster) {
            return this.blockEntity == otherThruster.blockEntity;
        }
        return false;
    }

    @Override
    public void attach(@NotNull IComputerAccess computer) {
        super.attach(computer);
        blockEntity.setControlMode(AbstractThrusterBlockEntity.ControlMode.PERIPHERAL);
    }

    @Override
    public void detach(@NotNull IComputerAccess computer) {
        super.detach(computer);
        blockEntity.setDigitalInput(0.0f);
        blockEntity.setControlMode(AbstractThrusterBlockEntity.ControlMode.NORMAL);
    }
}