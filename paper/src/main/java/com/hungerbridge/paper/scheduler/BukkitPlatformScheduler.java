package com.hungerbridge.paper.scheduler;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;

/** Paper / Purpur: a single main thread owns all state. */
final class BukkitPlatformScheduler implements PlatformScheduler {

    private final Plugin plugin;

    BukkitPlatformScheduler(Plugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String platformName() {
        return "paper";
    }

    @Override
    public boolean isFolia() {
        return false;
    }

    @Override
    public void runGlobal(Runnable task) {
        if (Bukkit.isPrimaryThread()) {
            task.run();
        } else {
            Bukkit.getScheduler().runTask(plugin, task);
        }
    }

    @Override
    public <T> Future<T> submitGlobal(Callable<T> task) {
        if (Bukkit.isPrimaryThread()) return PlatformScheduler.callNow(task);
        try {
            return Bukkit.getScheduler().callSyncMethod(plugin, task);
        } catch (RuntimeException e) { // plugin disabled / server shutting down
            return CompletableFuture.failedFuture(e);
        }
    }

    @Override
    public <T> Future<T> submitInWorld(World world, Callable<T> task) {
        return submitGlobal(task);
    }
}
