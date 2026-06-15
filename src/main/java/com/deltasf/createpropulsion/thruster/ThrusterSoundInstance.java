package com.deltasf.createpropulsion.thruster;

import com.deltasf.createpropulsion.registries.PropulsionSoundEvents;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

public class ThrusterSoundInstance extends AbstractTickableSoundInstance {
    public final AbstractThrusterBlockEntity blockEntity;

    public ThrusterSoundInstance(AbstractThrusterBlockEntity blockEntity) {
        super(PropulsionSoundEvents.THRUSTER.get(), SoundSource.BLOCKS, SoundInstance.createUnseededRandom());
        this.blockEntity = blockEntity;
        looping = true;
        delay = 0;
        Vec3 pos = blockEntity.getBlockPos().getCenter();
        x = pos.x;
        y = pos.y;
        z = pos.z;
    }

    @Override
    public boolean canStartSilent() {
        return true;
    }

    @Override
    public void tick() {
        volume = Mth.sqrt(blockEntity.getPower());
    }
}
