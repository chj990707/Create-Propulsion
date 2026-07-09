package com.deltasf.createpropulsion.helm;

import java.util.function.Supplier;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.network.NetworkEvent;

/** S2C: syncs a helm's wheel angle to clients tracking its chunk. */
public class SyncHelmWheelPacket {
    private final BlockPos pos;
    private final int wheelAngle;

    public SyncHelmWheelPacket(BlockPos pos, int wheelAngle) {
        this.pos = pos;
        this.wheelAngle = wheelAngle;
    }

    public SyncHelmWheelPacket(FriendlyByteBuf buf) {
        this.pos = buf.readBlockPos();
        this.wheelAngle = buf.readVarInt();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBlockPos(pos);
        buf.writeVarInt(wheelAngle);
    }

    public void handle(Supplier<NetworkEvent.Context> context) {
        if (FMLEnvironment.dist.isClient()) HelmClientHandler.handleWheelAngle(pos, wheelAngle);
        context.get().setPacketHandled(true);
    }
}
