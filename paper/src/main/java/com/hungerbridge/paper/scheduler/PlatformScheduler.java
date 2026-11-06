package com.hungerbridge.paper.scheduler;

import org.bukkit.World;
import org.bukkit.plugin.Plugin;

import java.util.concurrent.Callable;

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
 * <p>All {@code call*} methods block the calling (HTTP) thread for at most
 * {@code timeoutMs} and return {@code fallback} on timeout or failure, so a stalled
 * server can never hang the HTTP API.
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

    /** "paper" or "folia" — reported as the platform in {@code /server/meta}. */
    String platformName();

    boolean isFolia();

    /** Fire-and-forget on the thread that owns server-wide state. */
    void runGlobal(Runnable task);

    /** Run on the thread that owns server-wide state and wait for the result. */
    <T> T callGlobal(Callable<T> task, T fallback, long timeoutMs);

    /** Run on the thread that owns {@code world}'s spawn region and wait for the result. */
    <T> T callInWorld(World world, Callable<T> task, T fallback, long timeoutMs);
}
