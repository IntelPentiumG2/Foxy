package com.leclowndu93150.foxy.compat;

import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Stops Voxy SeedGen painting a tile once the world is closing.
 *
 * <p>SeedGen asks its workers to stop between tiles, but a tile paints the mod features of up to 64
 * chunks one at a time under a single lock, and in a large pack one chunk can take seconds: Streams
 * Reflowing builds whole river networks from inside its features. Leaving the world then waits for
 * every worker's tile to finish, and Voxy holds the client on "Saving world" until they do. Throwing
 * here ends the tile at the next chunk instead. SeedGen catches it as a failed worker before it marks
 * the tile written, so the tile is simply drawn again next time.
 *
 * <p>SeedGen's own signal, {@code WorldClosing.leaving()}, is raised too late to help: by the time it
 * is set, Voxy is already waiting on the render thread for the workers. So the integrated server is
 * watched as well. Once the server a SeedGen world was first painted under stops, the world is going, whatever SeedGen
 * has been told. On a client connected to a remote server there is none to watch, and only SeedGen's
 * signal counts.
 *
 * <p>SeedGen is reached by reflection so Foxy builds without it.
 */
public final class SeedGenClosing {
    private static volatile boolean resolved;
    private static MethodHandle leaving;
    /** The server each SeedGen world was painted for, so one world's server never ends another's tiles. */
    private static final Map<Object, MinecraftServer> SERVERS = Collections.synchronizedMap(new WeakHashMap<>());

    private SeedGenClosing() {}

    public static void abortIfLeaving(Object painted) {
        if (serverGone(painted) || seedGenLeaving(painted)) {
            throw new WorldClosingException();
        }
    }

    private static boolean serverGone(Object painted) {
        MinecraftServer known = SERVERS.get(painted);
        if (known != null) {
            return !known.isRunning();
        }
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server != null && server.isRunning()) {
            SERVERS.put(painted, server);
        }
        return false;
    }

    private static boolean seedGenLeaving(Object painted) {
        if (!resolved) {
            synchronized (SeedGenClosing.class) {
                if (!resolved) {
                    try {
                        Class<?> closing = Class.forName("mobius.voxyseedgen.voxy.WorldClosing", false,
                                painted.getClass().getClassLoader());
                        leaving = MethodHandles.publicLookup().findStatic(closing, "leaving",
                                MethodType.methodType(boolean.class));
                    } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                        leaving = null;
                    }
                    resolved = true;
                }
            }
        }
        if (leaving == null) {
            return false;
        }
        try {
            return (boolean) leaving.invokeExact();
        } catch (Throwable t) {
            return false;
        }
    }

    /** Thrown to end a tile early; the message is what SeedGen's log line will show. */
    public static final class WorldClosingException extends RuntimeException {
        WorldClosingException() {
            super("the world is closing, so Foxy ended this tile early; it is drawn again next time", null, false, false);
        }
    }
}
