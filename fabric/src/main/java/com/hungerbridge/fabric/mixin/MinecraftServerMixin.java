package com.hungerbridge.fabric.mixin;

import com.hungerbridge.fabric.HungerBridgeFabric;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MinecraftServer.class)
public abstract class MinecraftServerMixin {

    @Unique
    private boolean hungerbridge$started = false;

    @Unique
    private long hungerbridge$tickStartNanos;

    @Inject(method = "tickChildren", at = @At("HEAD"))
    private void hungerbridge$onTickChildrenStart(CallbackInfo ci) {
        if (!hungerbridge$started) {
            hungerbridge$started = true;
            HungerBridgeFabric.onServerStarted((MinecraftServer)(Object)this);
        }
        hungerbridge$tickStartNanos = System.nanoTime();
    }

    @Inject(method = "tickChildren", at = @At("TAIL"))
    private void hungerbridge$onTickChildrenEnd(CallbackInfo ci) {
        long end = System.nanoTime();
        long duration = end - hungerbridge$tickStartNanos;
        if (duration > 0L) {
            HungerBridgeFabric.recordTick(duration);
        }
    }

    @Inject(method = "stopServer", at = @At("HEAD"))
    private void hungerbridge$onStop(CallbackInfo ci) {
        HungerBridgeFabric.onServerStopping();
    }

    @Inject(method = "reloadResources", at = @At("HEAD"))
    private void hungerbridge$onReload(CallbackInfo ci) {
        try {
            HungerBridgeFabric.onServerReload((MinecraftServer)(Object)this);
        } catch (Throwable ignored) {}
    }
}
