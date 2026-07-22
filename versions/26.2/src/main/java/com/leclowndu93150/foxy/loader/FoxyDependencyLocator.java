package com.leclowndu93150.foxy.loader;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.neoforged.fml.jarcontents.JarContents;
import net.neoforged.fml.loading.moddiscovery.readers.JarModsDotTomlModFileReader;
import net.neoforged.neoforgespi.locating.IDependencyLocator;
import net.neoforged.neoforgespi.locating.IDiscoveryPipeline;
import net.neoforged.neoforgespi.locating.IModFile;
import net.neoforged.neoforgespi.locating.ModFileDiscoveryAttributes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class FoxyDependencyLocator implements IDependencyLocator {
    private static final Logger LOGGER = LoggerFactory.getLogger("Foxy");

    /**
     * A nested jar pulled out of a Fabric mod, along with the packages it would contribute.
     */
    private record Candidate(String name, Path path, Set<String> packages) {}

    @Override
    public void scanMods(List<IModFile> loadedMods, IDiscoveryPipeline pipeline) {
        List<IModFile> fabricMods = new ArrayList<>();
        List<IModFile> otherMods = new ArrayList<>();
        for (IModFile mod : loadedMods) {
            // Multiloader mods ship a fabric.mod.json next to their neoforge.mods.toml; NeoForge
            // already loads those and their nested jars. Only jars Foxy itself converted from
            // Fabric carry nested jars nobody else has handled.
            if (FoxyFabricModReader.isFoxyPatched(mod.getContents().getPrimaryPath())) {
                fabricMods.add(mod);
            } else {
                otherMods.add(mod);
            }
        }
        if (fabricMods.isEmpty()) {
            return;
        }

        List<Candidate> candidates = new ArrayList<>();
        for (IModFile mod : fabricMods) {
            candidates.addAll(extractNestedJars(mod.getContents()));
        }
        if (candidates.isEmpty()) {
            return;
        }

        Set<String> wanted = new HashSet<>();
        for (Candidate candidate : candidates) {
            wanted.addAll(candidate.packages());
        }
        Map<String, String> providers = findProviders(otherMods, wanted);
        LOGGER.info("Extracted {} bundled jar(s) contributing {} package(s); {} already provided by {} other loaded mod(s)",
                candidates.size(), wanted.size(), providers.size(), otherMods.size());

        for (Candidate candidate : candidates) {
            String clash = firstProvided(candidate, providers);
            if (clash != null) {
                // Adding it anyway would put the same package in two modules, which the module
                // system rejects outright. NeoForge's own JarJar dedup misses this when the two
                // copies resolve to different module names.
                LOGGER.info("Skipping bundled {}: package {} is already provided by {}",
                        candidate.name(), clash.replace('/', '.'), providers.get(clash));
                deleteQuietly(candidate.path());
                continue;
            }
            try {
                pipeline.addModFile(IModFile.create(
                        JarContents.ofPath(candidate.path()),
                        JarModsDotTomlModFileReader::manifestParser,
                        IModFile.Type.GAMELIBRARY,
                        ModFileDiscoveryAttributes.DEFAULT));
            } catch (IOException e) {
                LOGGER.error("Failed to add bundled {}", candidate.name(), e);
            }
        }
    }

    private static String firstProvided(Candidate candidate, Map<String, String> providers) {
        for (String pkg : candidate.packages()) {
            if (providers.containsKey(pkg)) {
                return pkg;
            }
        }
        return null;
    }

    /**
     * Maps each of {@code wanted} that some already-loaded mod contains to that mod's file name.
     */
    private static Map<String, String> findProviders(List<IModFile> mods, Set<String> wanted) {
        Map<String, String> providers = new HashMap<>();
        if (wanted.isEmpty()) {
            return providers;
        }
        for (IModFile mod : mods) {
            if (providers.size() == wanted.size()) {
                break;
            }
            try {
                mod.getContents().visitContent((name, resource) -> {
                    String pkg = packageOf(name);
                    if (pkg != null && wanted.contains(pkg)) {
                        providers.putIfAbsent(pkg, mod.getFileName());
                    }
                });
            } catch (RuntimeException e) {
                LOGGER.debug("Could not scan {} for duplicate packages", mod.getFileName(), e);
            }
        }
        return providers;
    }

    private static List<Candidate> extractNestedJars(JarContents contents) {
        List<Candidate> candidates = new ArrayList<>();
        JsonArray jars = readJarsArray(contents);
        if (jars == null) {
            return candidates;
        }
        for (JsonElement entry : jars) {
            if (!entry.isJsonObject()) continue;
            JsonElement file = entry.getAsJsonObject().get("file");
            if (file == null || !file.isJsonPrimitive()) continue;
            String innerPath = file.getAsString();
            try {
                Path extracted = extract(contents, innerPath);
                if (extracted != null) {
                    String name = innerPath.substring(innerPath.lastIndexOf('/') + 1);
                    candidates.add(new Candidate(name, extracted, packagesOf(extracted)));
                }
            } catch (IOException ignored) {}
        }
        return candidates;
    }

    private static Set<String> packagesOf(Path jar) {
        Set<String> packages = new HashSet<>();
        try (JarContents contents = JarContents.ofPath(jar)) {
            contents.visitContent((name, resource) -> {
                String pkg = packageOf(name);
                if (pkg != null) {
                    packages.add(pkg);
                }
            });
        } catch (IOException | RuntimeException ignored) {}
        return packages;
    }

    /**
     * Directory of a class file, or null for anything that does not contribute a package:
     * resources, metadata, and the module descriptor itself.
     */
    private static String packageOf(String name) {
        if (!name.endsWith(".class") || name.startsWith("META-INF/")) {
            return null;
        }
        // A root module-info.class has no slash and is filtered out here too.
        int slash = name.lastIndexOf('/');
        return slash < 0 ? null : name.substring(0, slash);
    }

    private static JsonArray readJarsArray(JarContents contents) {
        try (InputStream is = contents.openFile("fabric.mod.json")) {
            if (is == null) return null;
            JsonObject obj = JsonParser.parseReader(new InputStreamReader(is, StandardCharsets.UTF_8)).getAsJsonObject();
            JsonElement jars = obj.get("jars");
            return jars != null && jars.isJsonArray() ? jars.getAsJsonArray() : null;
        } catch (IOException | IllegalStateException e) {
            return null;
        }
    }

    private static Path extract(JarContents contents, String innerPath) throws IOException {
        if (!contents.containsFile(innerPath)) {
            return null;
        }
        String name = innerPath.substring(innerPath.lastIndexOf('/') + 1);
        Path out = Files.createTempFile("foxy-jij-", "-" + name);
        out.toFile().deleteOnExit();
        try (InputStream is = contents.openFile(innerPath)) {
            Files.copy(is, out, StandardCopyOption.REPLACE_EXISTING);
        }
        return out;
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {}
    }

    @Override
    public int getPriority() {
        return LOWEST_SYSTEM_PRIORITY;
    }
}
