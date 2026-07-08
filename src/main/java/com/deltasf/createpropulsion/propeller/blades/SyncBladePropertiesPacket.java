package com.deltasf.createpropulsion.propeller.blades;

import com.deltasf.createpropulsion.network.SyncThrusterFuelsPacket;
import com.deltasf.createpropulsion.thruster.FluidThrusterProperties;
import com.deltasf.createpropulsion.thruster.ThrusterFuelManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.material.Fluid;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

public class SyncBladePropertiesPacket {
    private Map<ResourceLocation, BladeProperties> BLADES = new HashMap<>();

    public static SyncBladePropertiesPacket create(Map<ResourceLocation, BladeProperties> mapToSync) {
        Map<ResourceLocation, BladeProperties> networkSafeMap = new HashMap<>();
        mapToSync.forEach((key, props) -> {
            if (key != null) {
                networkSafeMap.put(key, props);
            }
        });
        return new SyncBladePropertiesPacket(networkSafeMap);
    }

    private SyncBladePropertiesPacket(Map<ResourceLocation, BladeProperties> fuelMap) {
        this.BLADES = fuelMap;
    }

    public static SyncBladePropertiesPacket decode(FriendlyByteBuf buf) {
        Map<ResourceLocation, BladeProperties> map = buf.readMap(FriendlyByteBuf::readResourceLocation, BladeProperties::decode);
        return new SyncBladePropertiesPacket(map);
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeMap(this.BLADES, FriendlyByteBuf::writeResourceLocation, (b, props) -> props.encode(b));
    }

    public void handle(Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        context.enqueueWork(() -> {
            BladeDataManager.updateClient(this.BLADES);
        });
        context.setPacketHandled(true);
    }
}
