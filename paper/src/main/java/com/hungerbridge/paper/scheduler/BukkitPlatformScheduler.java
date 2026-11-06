package com.hungerbridge.paper.scheduler;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

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
    public <T> T callGlobal(Callable<T> task, T fallback, long timeoutMs) {
        if (Bukkit.isPrimaryThread()) {
            try {
                return task.call();
            } catch (Exception e) {
                return fallback;
            }
        }
        Future<T> future;
        try {
            future = Bukkit.getScheduler().callSyncMethod(plugin, task);
        } catch (Exception e) { // plugin disabled / server shutting down
            return fallback;
        }
        try {
            T value = future.get(timeoutMs, TimeUnit.MILLISECONDS);
            return value != null ? value : fallback;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(false);
            return fallback;
        } catch (Exception e) {
            future.cancel(false);
            return fallback;
        }
    }

    @Override
    public <T> T callInWorld(World world, Callable<T> task, T fallback, long timeoutMs) {
        return callGlobal(task, fallback, timeoutMs);
    }
}
