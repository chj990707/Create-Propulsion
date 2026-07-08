package com.deltasf.createpropulsion.registries;

import com.deltasf.createpropulsion.CreatePropulsion;
import net.minecraft.sounds.SoundEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public class PropulsionSoundEvents {
    public static final DeferredRegister<SoundEvent> SOUND_EVENTS;

    public static RegistryObject<SoundEvent> PROPELLER_2_5;
    public static RegistryObject<SoundEvent> PROPELLER_5;
    public static RegistryObject<SoundEvent> PROPELLER_10;
    public static RegistryObject<SoundEvent> PROPELLER_20;
    public static RegistryObject<SoundEvent> PROPELLER_40;
    public static RegistryObject<SoundEvent> PROPELLER_80;
    public static RegistryObject<SoundEvent>[] PROP_SOUNDS;

    public static RegistryObject<SoundEvent> THRUSTER;

    private static RegistryObject<SoundEvent> registerSoundEvent(String name) {
        return SOUND_EVENTS.register(name, () -> SoundEvent.createVariableRangeEvent(CreatePropulsion.location(name)));
    }

    static {
        SOUND_EVENTS = DeferredRegister.create(ForgeRegistries.SOUND_EVENTS, CreatePropulsion.ID);
        PROPELLER_2_5 = registerSoundEvent("prop_1");
        PROPELLER_5 = registerSoundEvent("prop_2");
        PROPELLER_10 = registerSoundEvent("prop_3");
        PROPELLER_20 = registerSoundEvent("prop_4");
        PROPELLER_40 = registerSoundEvent("prop_5");
        PROPELLER_80 = registerSoundEvent("prop_6");
        PROP_SOUNDS = new RegistryObject[]{PROPELLER_2_5, PROPELLER_5, PROPELLER_10, PROPELLER_20, PROPELLER_40, PROPELLER_80};
        THRUSTER = registerSoundEvent("thruster_loop");
    }

    public static void register(IEventBus eventBus) {
        SOUND_EVENTS.register(eventBus);
    }
}
