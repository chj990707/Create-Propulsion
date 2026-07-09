package com.deltasf.createpropulsion.registries;

import com.deltasf.createpropulsion.CreatePropulsion;
import dev.engine_room.flywheel.lib.model.baked.PartialModel;
import net.minecraft.resources.ResourceLocation;

import java.util.HashMap;
import java.util.Map;

public class PropulsionPartialModels {
    public static final Map<String, PartialModel> BLADE_MODELS = new HashMap<>();

    //Lodestone
    public static final PartialModel LODESTONE_TRACKER_INDICATOR = partial("lodestone_tracker_overlay");
    //Hot air burner
    public static final PartialModel HOT_AIR_BURNER_LEVER = partial("hot_air_burner_lever");
    //Propeller
    public static final PartialModel PROPELLER_HEAD = partial("propeller_head");
    public static final PartialModel WOODEN_BLADE = partialBlade("wooden_blade");
    public static final PartialModel COPPER_BLADE = partialBlade("copper_blade");
    public static final PartialModel ANDESITE_BLADE = partialBlade("andesite_blade");
    //Reaction wheel
    public static final PartialModel REACTION_WHEEL_CORE = partial("reaction_wheel_core");
    //Stirling engine
    public static final PartialModel STIRLING_ENGINE_PISTON = partial("stirling_piston");
    //Liquid burner
    public static final PartialModel LIQUID_BURNER_FAN = partial("liquid_burner_fan");
    //Tilt adapter
    public static final PartialModel TILT_ADAPTER_INPUT_SHAFT = partial("tilt_adapter_input_shaft");
    public static final PartialModel TILT_ADAPTER_OUTPUT_SHAFT = partial("tilt_adapter_output_shaft");
    public static final PartialModel TILT_ADAPTER_GANTRY = partial("tilt_adapter_screw_overlay");
    public static final PartialModel TILT_ADAPTER_SIDE_INDICATOR = partial("tilt_adapter_side_overlay");
    //Creative thruster
    public static final PartialModel CREATIVE_THRUSTER_BRACKET = partial("creative_thruster_bracket");
    //Multiblock thruster
    // Authored in normal [0,16] block-model coords as if they were a 1x1x1
    // model. ThrusterRenderer scales by cube width at draw time, so the same
    // file stretches to cover the full multiblock. Modders replacing these
    // models should keep their authoring coords in [0,16].
    public static final PartialModel THRUSTER_MULTIBLOCK_2X2X2 = partial("thruster_multiblock_2x2x2");
    public static final PartialModel THRUSTER_MULTIBLOCK_3X3X3 = partial("thruster_multiblock_3x3x3");
    //Transmission
    public static final PartialModel TRANSMISSION_PLUS = partial("transmission_plus");
    public static final PartialModel TRANSMISSION_MINUS = partial("transmission_minus");
    //Redstone helm
    public static final PartialModel REDSTONE_HELM_WHEEL = partial("redstone_helm_wheel");
    //Hot air pump
    public static final PartialModel HOT_AIR_PUMP_COG = partial("hot_air_pump_cogwheel");
    public static final PartialModel HOT_AIR_PUMP_MEMBRANE = partial("hot_air_pump_membrane");
    public static final PartialModel HOT_AIR_PUMP_MESH = partial("hot_air_pump_mesh");

    private static PartialModel partial(String path) {
        return PartialModel.of(ResourceLocation.fromNamespaceAndPath(CreatePropulsion.ID, "partial/" + path));
    }

    private static PartialModel partialBlade(String path) {
        PartialModel model = partial(path);
        BLADE_MODELS.put(CreatePropulsion.ID + ":block/" + path, model);
        return model;
    }


    public static void register() {}
}