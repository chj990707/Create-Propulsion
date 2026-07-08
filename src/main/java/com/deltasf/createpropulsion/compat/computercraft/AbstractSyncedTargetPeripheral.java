package com.deltasf.createpropulsion.compat.computercraft;

import com.simibubi.create.compat.computercraft.implementation.peripherals.SyncedPeripheral;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;

public abstract class AbstractSyncedTargetPeripheral<T extends SmartBlockEntity> extends SyncedPeripheral<T> {
    public AbstractSyncedTargetPeripheral(T blockEntity) {
        super(blockEntity);
    }

    @Override
    public Object getTarget() {
        return blockEntity;
    }
}
