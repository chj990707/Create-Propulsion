package com.deltasf.createpropulsion.propeller;

import com.deltasf.createpropulsion.registries.PropulsionSoundEvents;
import com.mojang.logging.LogUtils;
import net.minecraft.client.resources.sounds.AbstractSoundInstance;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

public class PropellerSoundInstance extends AbstractTickableSoundInstance {
    protected final PropellerBlockEntity blockEntity;

    public PropellerSoundInstance(PropellerBlockEntity blockEntity) {
        super(PropulsionSoundEvents.PROPELLER_SOUND.get(), SoundSource.NEUTRAL, SoundInstance.createUnseededRandom());
        looping = true;
        delay = 0;
        Vec3 pos = blockEntity.getBlockPos().getCenter();
        x = pos.x;
        y = pos.y;
        z = pos.z;
        this.blockEntity = blockEntity;
    }

    @Override
    public boolean canStartSilent() {
        return true;
    }

    @Override
    public void tick() {
        float absRPM = Mth.abs(blockEntity.visualRPM);
        if (absRPM == 0.0f || blockEntity.getBlade().isEmpty()) {
            volume = 0.0f;
        } else {
            volume = absRPM / 64 / blockEntity.getBlade().get().getGearRatio();
            pitch = absRPM / 4800 / Mth.PI * blockEntity.getBladeCount();
        }
    }
}
