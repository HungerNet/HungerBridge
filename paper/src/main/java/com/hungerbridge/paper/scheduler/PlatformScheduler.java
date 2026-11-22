package com.hungerbridge.paper.scheduler;

import org.bukkit.World;
import org.bukkit.plugin.Plugin;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Thread-affinity abstraction for the Bukkit family.
 *
 * <p>HungerBridge serves HTTP requests on its own worker threads, but most server state
 * may only be touched from the thread that owns it:
 * <ul>
 *   <li><b>Paper / Purpur</b>: everything belongs to the single main thread
 *       ({@link BukkitPlatformScheduler}).</li>
 *   <li><b>Folia</b>: there is no main thread. Server-wide state (console commands,
 *       world time/weather) belongs to the <i>global region</i>, chunks/entities belong
 *       to the <i>region</i> that owns their location, and {@code BukkitScheduler}
 *       throws {@link UnsupportedOperationException} ({@link FoliaPlatformScheduler}).</li>
 * </ul>
 *
 * Implementations only schedule; waiting (always bounded by a timeout) is shared here.
 */
public interface PlatformScheduler {

    /** Class that only exists on Folia (and its forks). */
    String FOLIA_MARKER_CLASS = "io.papermc.paper.threadedregions.RegionizedServer";

    static boolean isFoliaRuntime() {
        try {
            Class.forName(FOLIA_MARKER_CLASS, false, PlatformScheduler.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    static PlatformScheduler create(Plugin plugin) {
        return isFoliaRuntime() ? new FoliaPlatformScheduler(plugin) : new BukkitPlatformScheduler(plugin);
    }

    /** "paper" or "folia", reported as the platform in {@code /server/meta}. */
    String platformName();

    boolean isFolia();

    /** Fire-and-forget on the thread that owns server-wide state. */
    void runGlobal(Runnable task);

    /** Runs {@code task} on the thread that owns server-wide state (inline when already on it). */
    <T> Future<T> submitGlobal(Callable<T> task);

    /** Runs {@code task} on the thread that owns {@code world}'s spawn region. */
    <T> Future<T> submitInWorld(World world, Callable<T> task);

    /** {@link #submitGlobal} and wait; failures and timeouts are thrown. */
    default <T> T callGlobalOrThrow(Callable<T> task, long timeoutMs) throws TimeoutException, ExecutionException, InterruptedException {
        Future<T> future = submitGlobal(task);
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException | InterruptedException e) {
            future.cancel(false);
            throw e;
        }
    }

    /** {@link #submitGlobal} and wait; {@code fallback} on failure or timeout (metrics). */
    default <T> T callGlobal(Callable<T> task, T fallback, long timeoutMs) {
        return await(submitGlobal(task), fallback, timeoutMs);
    }

    /** {@link #submitInWorld} and wait; {@code fallback} on failure or timeout (metrics). */
    default <T> T callInWorld(World world, Callable<T> task, T fallback, long timeoutMs) {
        if (world == null) return fallback;
        return await(submitInWorld(world, task), fallback, timeoutMs);
    }

    private static <T> T await(Future<T> future, T fallback, long timeoutMs) {
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

    /** Runs {@code task} now; the result or failure as a completed future. */
    static <T> CompletableFuture<T> callNow(Callable<T> task) {
        CompletableFuture<T> future = new CompletableFuture<>();
        try {
            future.complete(task.call());
        } catch (Throwable t) {
            future.completeExceptionally(t);
        }
        return future;
    }
}
