package com.deltasf.createpropulsion;

import com.deltasf.createpropulsion.registries.*;

import com.deltasf.createpropulsion.compat.computercraft.CCProxy;
import com.deltasf.createpropulsion.network.PropulsionPackets;
import com.deltasf.createpropulsion.particles.ParticleTypes;
import com.mojang.logging.LogUtils;
import com.simibubi.create.compat.Mods;
import com.simibubi.create.foundation.data.CreateRegistrate;

import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.valkyrienskies.mod.common.ValkyrienSkiesMod;

@Mod(CreatePropulsion.ID)
public class CreatePropulsion {
    public static final String ID = "createpropulsion";
    public static final CreateRegistrate REGISTRATE = CreateRegistrate.create(ID);
    public static CreateRegistrate registrate() { return REGISTRATE; }
    public static ResourceLocation location(String path) { return ResourceLocation.fromNamespaceAndPath(ID, path); }

    public CreatePropulsion(FMLJavaModLoadingContext ctx) {
        IEventBus modBus = ctx.getModEventBus();
        //Content
        PropulsionSoundEvents.register(modBus);
        ParticleTypes.register(modBus);
        PropulsionBlocks.register();
        PropulsionBlockEntities.register();
        PropulsionItems.register();
        PropulsionFluids.register();
        PropulsionPartialModels.register();
        PropulsionCreativeTab.register(modBus);
        PropulsionPackets.register();
        PropulsionDisplaySources.register();
        PropulsionValkyrien.init();

        //Compat
        Mods.COMPUTERCRAFT.executeIfInstalled(() -> CCProxy::register);

        //Config
        ctx.registerConfig(ModConfig.Type.SERVER, PropulsionConfig.SERVER_SPEC, ID + "-server.toml");
        ctx.registerConfig(ModConfig.Type.CLIENT, PropulsionConfig.CLIENT_SPEC, ID + "-client.toml");
        PropulsionDefaultStress.init(PropulsionConfig.SERVER_SPEC);
        
        REGISTRATE.registerEventListeners(modBus);
    }
}
