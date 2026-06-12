package com.deltasf.createpropulsion.registries;

import com.deltasf.createpropulsion.CreatePropulsion;
import net.minecraft.sounds.SoundEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public class PropulsionSoundEvents {
    public static final DeferredRegister<SoundEvent> SOUND_EVENTS;
    public static RegistryObject<SoundEvent> PROPELLER_SOUND;

    private static RegistryObject<SoundEvent> registerSoundEvent(String name) {
        return SOUND_EVENTS.register(name, () -> SoundEvent.createVariableRangeEvent(CreatePropulsion.location(name)));
    }

    static {
        SOUND_EVENTS = DeferredRegister.create(ForgeRegistries.SOUND_EVENTS, CreatePropulsion.ID);
        PROPELLER_SOUND = registerSoundEvent("prop_loop");
    }

    public static void register(IEventBus eventBus) {
        SOUND_EVENTS.register(eventBus);
    }
}
