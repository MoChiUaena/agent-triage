package io.github.mochiuaena.triage.sdk;

import java.io.*;
import java.net.*;
import java.util.*;

/** Looks within the declaring class's code origin, never a global first-match classpath resource. */
final class SourceBuildVersions {
    private static final Map<Class<?>, Optional<String>> CACHE = Collections.synchronizedMap(new WeakHashMap<>());
    private SourceBuildVersions() {}
    static String sourceHash(Class<?> type) {
        synchronized (CACHE) {
            var cached = CACHE.get(type); if (cached != null) return cached.orElse(null);
            String value = lookup(type); if (CACHE.size() >= 256) CACHE.clear(); CACHE.put(type, Optional.ofNullable(value)); return value;
        }
    }
    private static String lookup(Class<?> type) {
        try {
            var domain = type.getProtectionDomain(); var code = domain == null ? null : domain.getCodeSource();
            if (code == null || code.getLocation() == null) return null;
            URL origin = code.getLocation(); String text = origin.toExternalForm();
            if (!(text.startsWith("file:") || text.startsWith("jar:file:") || text.startsWith("jar:nested:"))) return null;
            if (text.startsWith("file:") && (!origin.getHost().isEmpty() || origin.getPath().startsWith("//"))) return null;
            URL root = text.startsWith("file:") && !text.endsWith("/") ? URI.create("jar:" + text + "!/").toURL() : origin;
            byte[] manifest = read(new URL(manifestRoot(root), SourceBuildManifest.RESOURCE), SourceBuildManifest.MAX_MANIFEST_BYTES);
            var properties = new Properties(); properties.load(new StringReader(new String(manifest, java.nio.charset.StandardCharsets.UTF_8)));
            if (!"1".equals(properties.getProperty("format")) || properties.size() > 10001) return null;
            String item = properties.getProperty(type.getName());
            if (item == null || !item.matches("[a-f0-9]{64},[a-f0-9]{64}")) return null;
            byte[] bytes = read(new URL(root, type.getName().replace('.', '/') + ".class"), SourceBuildManifest.MAX_CLASS_BYTES);
            if (!BuildClassFile.read(bytes).name().equals(type.getName()) || !SourceBuildManifest.digest(bytes).equals(item.substring(65))) return null;
            return item.substring(0, 64);
        } catch (Exception | LinkageError ignored) { return null; }
    }
    private static URL manifestRoot(URL root) throws MalformedURLException, URISyntaxException {
        String text = root.toExternalForm();
        // Boot relocates application META-INF entries to the outer archive root.
        String nested = "/!BOOT-INF/classes/!/";
        if (text.startsWith("jar:nested:") && text.endsWith(nested)) {
            String path = text.substring("jar:nested:".length(), text.length() - nested.length());
            return URI.create("jar:" + new URI("file", null, path, null).toASCIIString() + "!/").toURL();
        }
        String classes = "!/BOOT-INF/classes/";
        if (text.startsWith("jar:file:") && text.endsWith(classes)) return URI.create(text.substring(0, text.length() - classes.length()) + "!/").toURL();
        return root;
    }
    private static byte[] read(URL resource, int limit) throws IOException {
        var connection = resource.openConnection(); connection.setUseCaches(false);
        try (var input = connection.getInputStream()) {
            byte[] bytes = input.readNBytes(limit + 1); if (bytes.length > limit) throw new IOException("Build resource too large"); return bytes;
        }
    }
}
