package com.leclowndu93150.foxy.loader;

import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Content-addressed store for the jars Foxy derives from Fabric mods: the patched mod jars and
 * the libraries nested inside them.
 *
 * <p>These used to be fresh temp files on every launch. {@code deleteOnExit} never runs when the
 * launcher kills the game, so each launch leaked a few hundred megabytes into {@code /tmp}, which
 * is often RAM. Naming each file after a hash of its bytes lets later launches reuse it, and
 * {@link #prune()} drops whatever the current launch did not ask for.
 */
public final class FoxyCache {
    private static final Logger LOGGER = LoggerFactory.getLogger("Foxy");

    /** Files stored or reused by this launch; everything else at the top level is stale. */
    private static final Set<Path> USED = ConcurrentHashMap.newKeySet();

    /** A temp file younger than this may belong to another launch that is writing it right now. */
    private static final long TMP_GRACE_MILLIS = TimeUnit.HOURS.toMillis(1);

    private FoxyCache() {}

    public static Path dir() {
        return FMLPaths.GAMEDIR.get().resolve(".foxy-cache");
    }

    /**
     * Returns {@code <dir>/<base>-<hash>.jar} holding {@code bytes}, writing it only if no launch
     * has before. Written through a temp file and an atomic move, so a crash or a second game
     * sharing the directory never leaves a half-written jar under the final name.
     */
    static Path store(String fileName, byte[] bytes) throws IOException {
        String base = fileName.endsWith(".jar") ? fileName.substring(0, fileName.length() - 4) : fileName;
        Path dir = Files.createDirectories(dir());
        Path target = dir.resolve(base + "-" + shortHash(bytes) + ".jar");
        if (!Files.isRegularFile(target)) {
            Path tmp = Files.createTempFile(dir, base + "-", ".tmp");
            try {
                Files.write(tmp, bytes);
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                // Another launch can win the race with identical bytes and hold the file open.
                if (!Files.isRegularFile(target)) throw e;
            } finally {
                Files.deleteIfExists(tmp);
            }
        }
        USED.add(target);
        return target;
    }

    /**
     * Deletes cache entries this launch did not use, plus the per-launch temp files older Foxy
     * versions left in {@code java.io.tmpdir}. Files still open elsewhere may refuse deletion on
     * Windows; they are skipped and retried next launch.
     */
    static void prune() {
        long cutoff = System.currentTimeMillis() - TMP_GRACE_MILLIS;
        int deleted = 0;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir())) {
            for (Path file : files) {
                if (!Files.isRegularFile(file) || USED.contains(file)) continue;
                if (file.getFileName().toString().endsWith(".tmp") && Files.getLastModifiedTime(file).toMillis() > cutoff) continue;
                if (deleteQuietly(file)) deleted++;
            }
        } catch (NoSuchFileException ignored) {
        } catch (IOException e) {
            LOGGER.warn("Could not prune {}", dir(), e);
        }
        try (DirectoryStream<Path> files = Files.newDirectoryStream(
                Paths.get(System.getProperty("java.io.tmpdir")), "{foxy-jij-,foxy-patched-}*")) {
            for (Path file : files) {
                if (Files.isRegularFile(file) && deleteQuietly(file)) deleted++;
            }
        } catch (IOException ignored) {}
        if (deleted > 0) {
            LOGGER.info("Pruned {} stale cached jar(s)", deleted);
        }
    }

    /**
     * Loads RocksDB's native library from {@code <dir>/natives} instead of letting RocksDB extract
     * a randomly named copy into {@code java.io.tmpdir} on every launch. RocksDB only reads its
     * directory from the {@code ROCKSDB_SHAREDLIB_DIR} environment variable, which a running JVM
     * cannot set, but its loader skips extraction once any call has loaded the library, so going
     * first is enough. That file still has a fixed name and is overwritten each launch.
     */
    public static void preloadRocksDbNatives() {
        Path natives = dir().resolve("natives");
        try {
            Class<?> loader = Class.forName("org.rocksdb.NativeLibraryLoader", true, FoxyCache.class.getClassLoader());
            Files.createDirectories(natives);
            Object instance = loader.getMethod("getInstance").invoke(null);
            loader.getMethod("loadLibrary", String.class).invoke(instance, natives.toString());
        } catch (ClassNotFoundException ignored) {
            // No RocksDB in the game, nothing to load.
        } catch (ReflectiveOperationException | IOException | RuntimeException | LinkageError e) {
            LOGGER.warn("Could not preload RocksDB natives into {}; RocksDB will extract them to java.io.tmpdir", natives, e);
        }
    }

    private static String shortHash(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            return HexFormat.of().formatHex(digest, 0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean deleteQuietly(Path path) {
        try {
            return Files.deleteIfExists(path);
        } catch (IOException e) {
            return false;
        }
    }
}
