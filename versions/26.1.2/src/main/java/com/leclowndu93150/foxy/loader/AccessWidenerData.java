package com.leclowndu93150.foxy.loader;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public final class AccessWidenerData {
    public final Set<String> classes = new HashSet<>();
    public final Map<String, Set<String>> fields = new HashMap<>();
    public final Map<String, Set<String>> methods = new HashMap<>();

    private static AccessWidenerData instance;

    public static synchronized AccessWidenerData get() {
        if (instance == null) {
            instance = load();
        }
        return instance;
    }

    private static AccessWidenerData load() {
        AccessWidenerData data = new AccessWidenerData();
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        try {
            Enumeration<URL> urls = cl.getResources("fabric.mod.json");
            while (urls.hasMoreElements()) {
                URL url = urls.nextElement();
                String awName = readAwName(url);
                if (awName == null) continue;
                try (InputStream is = cl.getResourceAsStream(awName)) {
                    if (is != null) {
                        data.parse(is);
                    }
                }
            }
        } catch (IOException ignored) {}
        try (InputStream extra = cl.getResourceAsStream("foxy-extra.accesswidener")) {
            if (extra != null) {
                data.parse(extra);
            }
        } catch (IOException ignored) {}
        return data;
    }

    private static String readAwName(URL fabricModJson) {
        try (InputStream is = fabricModJson.openStream()) {
            JsonObject obj = JsonParser.parseReader(new InputStreamReader(is, StandardCharsets.UTF_8)).getAsJsonObject();
            JsonElement aw = obj.get("accessWidener");
            return aw != null && aw.isJsonPrimitive() ? aw.getAsString() : null;
        } catch (IOException | IllegalStateException e) {
            return null;
        }
    }

    private void parse(InputStream is) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            boolean header = true;
            while ((line = reader.readLine()) != null) {
                int comment = line.indexOf('#');
                if (comment >= 0) line = line.substring(0, comment);
                line = line.strip();
                if (line.isEmpty()) continue;
                if (header) {
                    header = false;
                    continue;
                }
                String[] parts = line.split("\\s+");
                if (parts.length < 3) continue;
                String access = parts[0];
                if (!access.equals("accessible") && !access.equals("extendable") && !access.equals("mutable")) {
                    continue;
                }
                String type = parts[1];
                switch (type) {
                    case "class" -> classes.add(parts[2].replace('/', '.'));
                    case "field" -> {
                        if (parts.length >= 5) {
                            fields.computeIfAbsent(parts[2].replace('/', '.'), k -> new HashSet<>()).add(parts[3]);
                        }
                    }
                    case "method" -> {
                        if (parts.length >= 5) {
                            methods.computeIfAbsent(parts[2].replace('/', '.'), k -> new HashSet<>()).add(parts[3] + parts[4]);
                        }
                    }
                    default -> {}
                }
            }
        }
    }

    public Set<String> allTargets() {
        Set<String> all = new HashSet<>(classes);
        all.addAll(fields.keySet());
        all.addAll(methods.keySet());
        return all;
    }
}
