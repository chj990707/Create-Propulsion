package com.deltasf.createpropulsion.ponder;

import com.simibubi.create.content.redstone.analogLever.AnalogLeverBlockEntity;
import com.simibubi.create.foundation.ponder.CreateSceneBuilder;
import net.createmod.ponder.api.ParticleEmitter;
import net.createmod.ponder.api.element.ElementLink;
import net.createmod.ponder.api.element.WorldSectionElement;
import net.createmod.ponder.api.scene.SceneBuilder;
import net.createmod.ponder.api.scene.SceneBuildingUtil;
import net.createmod.ponder.api.scene.Selection;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

public class ThrusterScenes {
    public static void single(SceneBuilder builder, SceneBuildingUtil util) {
        CreateSceneBuilder scene = new CreateSceneBuilder(builder);
        scene.title("thruster_single", "Single Thrusters");
        scene.configureBasePlate(0, 0, 6);
        scene.showBasePlate();
        scene.idle(10);
        scene.world().showSection(util.select().layersFrom(1), Direction.DOWN);

        Selection lowerCog = util.select().position(5, 0, 4);
        scene.world().setKineticSpeed(lowerCog, 64);

        Selection upperCogs = util.select().fromTo(5, 1, 4, 1, 1, 4);
        scene.world().setKineticSpeed(upperCogs, -64);

        Selection pump = util.select().position(1, 1, 3);
        scene.world().setKineticSpeed(pump, 64);

//        scene.world().propagatePipeChange(pump.po);

        scene.overlay().showText(80)
                .sharedText("single_thruster.intro")
                .placeNearTarget()
                .pointAt(util.vector().topOf(util.grid().at(2, 1, 1)));
        scene.idle(80);
        scene.addKeyframe();

        Selection lever = util.select().position(1, 1, 1);
        scene.world().modifyBlockEntityNBT(lever, AnalogLeverBlockEntity.class, nbt -> {
            nbt.putInt("State", 7);
        });
        scene.overlay().showText(80)
                .sharedText("single_thruster.redstone_power_level")
                .placeNearTarget()
                .pointAt(util.vector().topOf(util.grid().at(1, 1, 1)));
        /* Thruster will cause a crash trying to emit particles during ponder lmao */
//        Selection thruster = util.select().position(2, 1, 1);
//        scene.world().modifyBlockEntityNBT(thruster, ThrusterBlockEntity.class, nbt -> {
//            nbt.putInt("RedstoneInput", 7);
//        });
        scene.idle(80);
        scene.addKeyframe();

        Vec3 center = util.vector().centerOf(util.grid().at(2, 1, 0));

        BlockParticleOption obsidianParticle =
                new BlockParticleOption(ParticleTypes.BLOCK, Blocks.OBSIDIAN.defaultBlockState());

        ParticleEmitter emitter = (level, x,y, z) -> {
            double ox = x + (level.random.nextDouble() - 0.5);
            double oy = y + (level.random.nextDouble() - 0.5);
            double oz = z + (level.random.nextDouble() - 0.5);
            level.addParticle(obsidianParticle, ox, oy, oz, 0, 0, 0);
        };
        scene.effects().emitParticles(center, emitter, 64f, 1);

        scene.world().setBlock(util.grid().at(2, 1, 0), Blocks.OBSIDIAN.defaultBlockState(), true);
        scene.overlay().showText(100)
                .sharedText("single_thruster.about_obstruction")
                .placeNearTarget()
                .pointAt(util.vector().topOf(util.grid().at(2, 1, 1)));
        scene.idle(100);
    }


    public static void multiblock(SceneBuilder builder, SceneBuildingUtil util) {
        CreateSceneBuilder scene = new CreateSceneBuilder(builder);

        scene.title("thruster_multiblock", "Multiblock Thrusters");
        scene.configureBasePlate(0, 0, 7);
        scene.showBasePlate();
        scene.idle(10);

        Selection multiBlockSelection = util.select().fromTo(2, 1, 1, 3, 2, 2);
        ElementLink<WorldSectionElement> multiSection = scene.world().showIndependentSection(
                multiBlockSelection, Direction.DOWN);

        scene.overlay().showText(80)
                .sharedText("multiblock_thruster.intro")
                .placeNearTarget()
                .pointAt(util.vector().topOf(util.grid().at(2, 2, 1)));
        scene.idle(80);


        scene.world().rotateSection(multiSection, 0, 360, 0, 48);
        scene.idle(54);
        scene.addKeyframe();

        scene.overlay().showText(80)
                .sharedText("multiblock_thruster.facing")
                .placeNearTarget()
                .pointAt(util.vector().centerOf(util.grid().at(2, 2, 1)));
        scene.idle(80);

        scene.addKeyframe();

        scene.world().setKineticSpeed(util.select().position(6,0,4), 64);
        scene.world().setKineticSpeed(util.select().fromTo(6,1,4, 3, 1, 4), -64);
        scene.world().setKineticSpeed(util.select().position( 3, 1, 5), 64);
        scene.world().setKineticSpeed(util.select().position( 3, 2, 5), -64); // Pump
        scene.world().setKineticSpeed(util.select().fromTo( 3, 1, 5, 0, 1, 5), -64);
        scene.world().setKineticSpeed(util.select().fromTo( 0, 1, 4, 0, 1, 3), -64);
        scene.world().setKineticSpeed(util.select().position( 1, 1, 3), 64);
        scene.world().showSection(util.select().layersFrom(1).substract(multiBlockSelection), Direction.DOWN);
        scene.overlay().showText(100)
                .sharedText("multiblock_thruster.about_oxidizer")
                .placeNearTarget()
                .pointAt(util.vector().topOf(util.grid().at(2, 2, 4)));
        scene.idle(100);

        scene.addKeyframe();

        scene.overlay().showText(100)
                .sharedText("multiblock_thruster.pipe_inputs_1")
                .placeNearTarget()
                .pointAt(util.vector().centerOf(util.grid().at(1, 1, 2)));
        scene.idle(100);

        scene.overlay().showText(100)
                .sharedText("multiblock_thruster.pipe_inputs_2")
                .placeNearTarget()
                .pointAt(util.vector().centerOf(util.grid().at(1, 1, 1)));
        scene.idle(100);

        scene.overlay().showText(100)
                .sharedText("multiblock_thruster.pipe_inputs_3")
                .placeNearTarget()
                .pointAt(util.vector().centerOf(util.grid().at(1, 1, 1)));
        scene.idle(100);

//        scene.world().setKineticSpeed();
    }

    public static void multiblock_efficiency(SceneBuilder builder, SceneBuildingUtil util) {
        CreateSceneBuilder scene = new CreateSceneBuilder(builder);

        scene.title("multiblock_efficiency", "Multiblock Efficiency");
        scene.configureBasePlate(0, 0, 7);
        scene.showBasePlate();
        scene.idle(10);

        Selection threeByThree = util.select().fromTo(1, 1, 1, 3, 3, 3);
        Selection twoByTwo = util.select().fromTo(4, 1, 5, 5, 2, 6);

        scene.world().showSection(twoByTwo, Direction.DOWN);

        scene.overlay().showText(80)
                .sharedText("multiblock_efficiency.power_1")
                .placeNearTarget()
                .pointAt(util.vector().centerOf(util.grid().at(6, 2, 4)));
        scene.idle(80);

        ElementLink<WorldSectionElement> multiSection = scene.world().showIndependentSection(
                threeByThree, Direction.DOWN);
        scene.overlay().showText(100)
                .sharedText("multiblock_efficiency.power_2")
                .placeNearTarget()
                .pointAt(util.vector().topOf(util.grid().at(6, 2, 5)));
        scene.idle(40);

        scene.world().rotateSection(multiSection, 0, 360, 0, 60);

        scene.idle(80);

        scene.addKeyframe();

        scene.overlay().showText(80)
                .sharedText("multiblock_efficiency.efficiency_1")
                .independent();

        scene.idle(80);

        scene.overlay().showText(80)
                .sharedText("multiblock_efficiency.efficiency_2")
                .independent();
        scene.idle(80);
    }

    }

