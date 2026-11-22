package com.hungerbridge.paper.scheduler;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;

/**
 * Folia: regionized multithreading. Never touches {@code BukkitScheduler} (it throws on
 * Folia). Uses only the Paper threaded-regions API, which is present in the Paper API
 * jar the plugin is compiled against, so the same jar runs on Paper and Folia.
 */
final class FoliaPlatformScheduler implements PlatformScheduler {

    private final Plugin plugin;

    FoliaPlatformScheduler(Plugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String platformName() {
        return "folia";
    }

    @Override
    public boolean isFolia() {
        return true;
    }

    @Override
    public void runGlobal(Runnable task) {
        if (Bukkit.isGlobalTickThread()) {
            task.run();
        } else {
            Bukkit.getGlobalRegionScheduler().execute(plugin, task);
        }
    }

    @Override
    public <T> Future<T> submitGlobal(Callable<T> task) {
        if (Bukkit.isGlobalTickThread()) return PlatformScheduler.callNow(task);
        CompletableFuture<T> future = new CompletableFuture<>();
        try {
            Bukkit.getGlobalRegionScheduler().execute(plugin, () -> complete(future, task));
        } catch (RuntimeException e) {
            future.completeExceptionally(e);
        }
        return future;
    }

    @Override
    public <T> Future<T> submitInWorld(World world, Callable<T> task) {
        int chunkX;
        int chunkZ;
        try {
            Location spawn = world.getSpawnLocation();
            chunkX = spawn.getBlockX() >> 4;
            chunkZ = spawn.getBlockZ() >> 4;
        } catch (RuntimeException e) {
            chunkX = 0;
            chunkZ = 0;
        }
        if (Bukkit.isOwnedByCurrentRegion(world, chunkX, chunkZ)) return PlatformScheduler.callNow(task);
        CompletableFuture<T> future = new CompletableFuture<>();
        try {
            Bukkit.getRegionScheduler().execute(plugin, world, chunkX, chunkZ, () -> complete(future, task));
        } catch (RuntimeException e) {
            future.completeExceptionally(e);
        }
        return future;
    }

    private static <T> void complete(CompletableFuture<T> future, Callable<T> task) {
        try {
            future.complete(task.call());
        } catch (Throwable t) {
            future.completeExceptionally(t);
        }
    }
}
