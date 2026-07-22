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

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

public class FoxyDependencyLocator implements IDependencyLocator {

    @Override
    public void scanMods(List<IModFile> loadedMods, IDiscoveryPipeline pipeline) {
        for (IModFile mod : loadedMods) {
            JarContents contents = mod.getContents();
            if (!contents.containsFile("fabric.mod.json")) {
                continue;
            }
            extractNestedJars(contents, pipeline);
        }
    }

    private static void extractNestedJars(JarContents contents, IDiscoveryPipeline pipeline) {
        JsonArray jars = readJarsArray(contents);
        if (jars == null) {
            return;
        }
        for (JsonElement entry : jars) {
            if (!entry.isJsonObject()) continue;
            JsonElement file = entry.getAsJsonObject().get("file");
            if (file == null || !file.isJsonPrimitive()) continue;
            try {
                Path extracted = extract(contents, file.getAsString());
                if (extracted != null) {
                    IModFile lib = IModFile.create(
                            JarContents.ofPath(extracted),
                            JarModsDotTomlModFileReader::manifestParser,
                            IModFile.Type.GAMELIBRARY,
                            ModFileDiscoveryAttributes.DEFAULT);
                    pipeline.addModFile(lib);
                }
            } catch (IOException ignored) {}
        }
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

    @Override
    public int getPriority() {
        return LOWEST_SYSTEM_PRIORITY;
    }
}
