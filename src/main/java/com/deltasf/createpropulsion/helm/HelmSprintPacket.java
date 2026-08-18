package com.deltasf.createpropulsion.helm;

import java.util.function.Supplier;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

/**
 * C2S: syncs the seated player's sprint key state to the helm.
 *
 * VS2's own SeatedControllingPlayer#sprintOn is filled from the passenger's
 * vanilla isSprinting flag, which can never turn on while riding an entity
 * (LocalPlayer#aiStep requires being on ground with forward impulse to start
 * sprinting), so the raw key state has to be synced separately.
 */
public class HelmSprintPacket {
    private final BlockPos pos;
    private final boolean sprinting;

    public HelmSprintPacket(BlockPos pos, boolean sprinting) {
        this.pos = pos;
        this.sprinting = sprinting;
    }

    public HelmSprintPacket(FriendlyByteBuf buf) {
        this.pos = buf.readBlockPos();
        this.sprinting = buf.readBoolean();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBlockPos(pos);
        buf.writeBoolean(sprinting);
    }

    public boolean handle(Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null) return;
            if (!(player.level().getBlockEntity(pos) instanceof RedstoneHelmBlockEntity helm)) return;
            helm.updateSprint(player, sprinting);
        });
        return true;
    }
}
