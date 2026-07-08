package com.deltasf.createpropulsion.compat.computercraft;

import com.deltasf.createpropulsion.lodestone_tracker.LodestoneTrackerBlockEntity;

import dan200.computercraft.api.lua.LuaFunction;
import dan200.computercraft.api.peripheral.IPeripheral;

public class LodestoneTrackerPeripheral extends AbstractSyncedTargetPeripheral<LodestoneTrackerBlockEntity> {
    public LodestoneTrackerPeripheral(LodestoneTrackerBlockEntity blockEntity) {
        super(blockEntity);
    }

    @Override
    public final String getType() {
        return "lodestone_tracker";
    }

    @LuaFunction
    public final float getAngle() {
        return blockEntity.getAngle();
    }

    @Override
    public boolean equals(IPeripheral other) {
        if (this == other) return true;
        if (other instanceof LodestoneTrackerPeripheral otherTracker) {
            return this.blockEntity == otherTracker.blockEntity;
        }
        return false;
    }
}
