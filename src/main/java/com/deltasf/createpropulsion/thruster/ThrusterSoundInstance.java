package com.deltasf.createpropulsion.thruster;

import com.deltasf.createpropulsion.registries.PropulsionSoundEvents;
import com.deltasf.createpropulsion.thruster.thruster.ThrusterBlockEntity;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import org.joml.Vector3d;
import org.valkyrienskies.core.api.ships.ClientShip;
import org.valkyrienskies.mod.common.VSGameUtilsKt;

public class ThrusterSoundInstance extends AbstractTickableSoundInstance {
    /** Ticks of zero power tolerated before the instance stops itself and
     *  releases its sound channel. Minecraft only has 247 channels, and a
     *  stopped-but-looping sound holds one forever, so idle thrusters must
     *  not linger. AbstractThrusterBlockEntity.ensureSoundInstance() revives
     *  the sound on the client tick after power returns. */
    private static final int IDLE_STOP_TICKS = 40;

    public final AbstractThrusterBlockEntity blockEntity;
    private int idleTicks;

    public ThrusterSoundInstance(AbstractThrusterBlockEntity blockEntity) {
        super(PropulsionSoundEvents.THRUSTER.get(), SoundSource.BLOCKS, SoundInstance.createUnseededRandom());
        this.blockEntity = blockEntity;
        looping = true;
        delay = 0;
        updatePosition();
    }

    @Override
    public boolean canStartSilent() {
        return true;
    }

    @Override
    public void tick() {
        // Release the channel instead of lingering: on removal, on demotion
        // to a non-controller multiblock cell, or after sustained idleness.
        // stop() is permanent for a SoundInstance -- revival happens by the
        // block entity queueing a fresh instance via ensureSoundInstance().
        if (blockEntity.isRemoved() || !blockEntity.shouldEmitSound()) {
            stop();
            return;
        }
        // Only hum while actually producing thrust: powered AND working
        // (valid fuel, plus oxidizer for multiblocks). shouldPlaySound() is
        // protected but this class shares the abstract BE's package. A
        // thruster that loses fuel/oxidizer mid-burn goes quiet immediately
        // and releases its channel after the idle grace period;
        // ensureSoundInstance() revives it when thrust resumes.
        if (!blockEntity.shouldPlaySound()) {
            volume = 0.0f;
            if (++idleTicks >= IDLE_STOP_TICKS) stop();
            return;
        }
        idleTicks = 0;
        updatePosition();
        // Scale with cube size. Vanilla clamps playback gain at 1.0 but
        // scales the attenuation RANGE with volume, so a 3x3x3 at full burn
        // carries ~3x as far (~48 blocks) and reaches full loudness at lower
        // power than a single would.
        float scale = (blockEntity instanceof ThrusterBlockEntity t && t.isMultiblock()) ? t.getWidth() : 1.0f;
        volume = Mth.sqrt(blockEntity.getPower()) * scale;
    }

    /** Sound instances are positioned in world space, but blocks on an
     *  assembled ship live at shipyard coordinates, far from where the ship
     *  renders. Re-project the block position through the ship's render
     *  transform every tick -- mirroring what emitParticles() does for the
     *  plume -- which also keeps the sound attached to a moving ship. For
     *  multiblock controllers the emission point is shifted from the
     *  controller's corner cell to the geometric center of the cube, in
     *  local space, BEFORE the ship transform. */
    private void updatePosition() {
        Vec3 center = blockEntity.getBlockPos().getCenter();
        double px = center.x, py = center.y, pz = center.z;
        if (blockEntity instanceof ThrusterBlockEntity t && t.isMultiblock() && t.isController()) {
            double half = (t.getWidth() - 1) / 2.0;
            px += half;
            py += half;
            pz += half;
        }
        Level level = blockEntity.getLevel();
        if (level instanceof ClientLevel clientLevel) {
            ClientShip ship = VSGameUtilsKt.getShipObjectManagingPos(clientLevel, blockEntity.getBlockPos());
            if (ship != null) {
                Vector3d p = ship.getRenderTransform().getShipToWorld()
                        .transformPosition(new Vector3d(px, py, pz));
                px = p.x;
                py = p.y;
                pz = p.z;
            }
        }
        x = px;
        y = py;
        z = pz;
    }
}