package com.deltasf.createpropulsion.propeller;

import com.deltasf.createpropulsion.registries.PropulsionSoundEvents;
import com.mojang.logging.LogUtils;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

public class PropellerSoundInstance extends AbstractTickableSoundInstance {
    protected final PropellerBlockEntity blockEntity;
    public final float frequency;
    public final int frequencyOrdinal;

    public static final float MIN_FREQ = 2.5f;
    public static final int MAX_FREQ_ORDINAL = 5;
    public static final float MAX_FREQ = 80;

    private boolean tooLow;
    private boolean tooHigh;

    public PropellerSoundInstance(PropellerBlockEntity blockEntity, int frequencyOrdinal) {
        super(PropulsionSoundEvents.PROP_SOUNDS[frequencyOrdinal].get(), SoundSource.NEUTRAL, SoundInstance.createUnseededRandom());
        frequency = 2.5f * (float) Math.pow(2, frequencyOrdinal);
        this.frequencyOrdinal = frequencyOrdinal;
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
        if (blockEntity.getBladeCount() == 0 || blockEntity.visualRPM == 0.0) {
            volume = 0.0f;
            return;
        }
        float absRPM = Mth.abs(blockEntity.visualRPM);
        // How many blades pass the same point per second.
        float bladeFreq = absRPM / 60 * blockEntity.getBladeCount();
        if (bladeFreq > MAX_FREQ && this.frequencyOrdinal == MAX_FREQ_ORDINAL) {
            pitch = 1.0f;
            volume = getVolumeLevel(bladeFreq);
            return;
        }
        float newPitch = bladeFreq / frequency;
        tooLow = newPitch > 2.0f;
        tooHigh = newPitch < 0.5f;
        if (tooLow || tooHigh) {
            volume = 0.0f;
        } else {
            pitch = newPitch;
            volume = Math.max(0.0f, (1.0f - (float) Math.abs(Math.log(newPitch) / Math.log(2.0)))) * getVolumeLevel(bladeFreq);
        }
    }

    public float getVolumeLevel(float bladeFreq) {
        return (float) Math.sqrt(Math.max(0.0, (bladeFreq - MIN_FREQ) / (MAX_FREQ - MIN_FREQ)));
    }

    public boolean isTooLow() { return tooLow; }

    public boolean isTooHigh() { return tooHigh; }
}
