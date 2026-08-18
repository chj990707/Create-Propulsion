package com.deltasf.createpropulsion.thruster.ion;

import net.minecraft.nbt.Tag;
import net.minecraftforge.energy.EnergyStorage;

/**
 * EnergyStorage used by the Ion Thruster.
 *
 * <p>Constructed with {@code maxExtract = 0} so external energy cables can
 * only push energy in, never pull it back out — standard pattern for an RF
 * sink. The thruster itself still needs to drain the buffer each tick, so
 * {@link #extractInternal(int, boolean)} bypasses the {@code maxExtract}
 * constraint by touching the {@code protected int energy} field directly.
 */
public class PropulsionEnergyStorage extends EnergyStorage {

    public PropulsionEnergyStorage(int capacity, int maxReceive) {
        super(capacity, maxReceive, 0);
    }

    /** Drain used by the thruster itself. Bypasses {@code maxExtract}. */
    public int extractInternal(int amount, boolean simulate) {
        if (amount <= 0) return 0;
        int taken = Math.min(this.energy, amount);
        if (!simulate) this.energy -= taken;
        return taken;
    }

    /** Accessor kept for clarity on the call site — {@link #getEnergyStored()} works too. */
    public int getStored()    { return this.energy; }
    public int getCapacity()  { return this.capacity; }

    /** Convenience so callers don't need to import {@link Tag}. */
    public Tag save()              { return this.serializeNBT(); }
    public void load(Tag tag)      { this.deserializeNBT(tag); }
}