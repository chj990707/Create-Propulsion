package com.deltasf.createpropulsion.helm;

import org.valkyrienskies.mod.common.entity.ShipMountingEntity;

import com.deltasf.createpropulsion.network.PropulsionPackets;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/** Client-only logic, kept in its own class so the server never loads client classes. */
public class HelmClientHandler {
    //Refresh cadence for the sprint keepalive; must stay well below the server timeout
    private static final int SPRINT_RESEND_TICKS = 10;
    //Squared distance tolerance when matching the vehicle to this helm's seat
    private static final double SEAT_MATCH_EPSILON = 0.02;

    public static void handleWheelAngle(BlockPos pos, int wheelAngle) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) return;
        BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof RedstoneHelmBlockEntity helm) {
            helm.setSyncedWheelAngle(wheelAngle);
        }
    }

    /**
     * Runs from the helm's client tick: if the local player is seated at this
     * helm, sync the raw sprint key state to the server. Sends on change and
     * refreshes periodically while held so the server-side flag can expire
     * safely on dismount or disconnect.
     */
    public static void sprintKeyTick(Level level, BlockPos pos, BlockState state, RedstoneHelmBlockEntity helm) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) return;

        boolean seatedHere = false;
        if (player.getVehicle() instanceof ShipMountingEntity seat) {
            Vec3 expected = RedstoneHelmBlockEntity.seatPosition(pos, state.getValue(RedstoneHelmBlock.FACING));
            seatedHere = seat.position().distanceToSqr(expected) < SEAT_MATCH_EPSILON;
        }

        boolean sprint = seatedHere && mc.options.keySprint.isDown();
        long time = level.getGameTime();
        boolean changed = sprint != helm.clientSprintSent;
        boolean keepalive = sprint && time - helm.clientLastSprintSend >= SPRINT_RESEND_TICKS;
        if (changed || keepalive) {
            PropulsionPackets.sendToServer(new HelmSprintPacket(pos, sprint));
            helm.clientSprintSent = sprint;
            helm.clientLastSprintSend = time;
        }
    }
}
