package com.deltasf.createpropulsion.mixin.feature;

import com.deltasf.createpropulsion.mixin.plugin.MixinIf;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.valkyrienskies.mod.mixinducks.client.world.ClientChunkCacheDuck;

import javax.annotation.Nullable;

// TODO: Remove this in VS 2.5
@MixinIf("is_vs_2.5")
@Mixin(Minecraft.class)
public class MixinMinecraft {
    @Shadow
    @Nullable
    public ClientLevel level;

    @Inject(
            method = "tick",
            at = @At("TAIL")
    )
    private void flushChunkUnload(CallbackInfo ci) {
        if (level != null && level.getChunkSource() instanceof ClientChunkCacheDuck chunkCacheDuck) {
            chunkCacheDuck.vs$drainShipChunkUnloadQueue();;
        }
    }
}
