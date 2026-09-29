package io.github.mochiuaena.triage.sdk;

import com.sun.source.util.JavacTask;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import javax.tools.*;

/** Build-time CLI. Records digests only; parsing does not run annotation processors. */
public final class SourceBuildManifest {
    static final String RESOURCE = "META-INF/triage/source-digests-v1.properties";
    static final int MAX_CLASS_BYTES = 1024 * 1024;
    static final int MAX_MANIFEST_BYTES = 1024 * 1024;
    private SourceBuildManifest() {}
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Expected Java source directory and compiled classes directory");
        var result = generate(Path.of(args[0]), Path.of(args[1]));
        System.out.println("Triage build manifest: " + result + " class source digests");
    }
    static int generate(Path sourceDirectory, Path classesDirectory) throws Exception {
        Path sources = sourceDirectory.toRealPath(), classes = classesDirectory.toRealPath();
        if (!Files.isDirectory(sources) || !Files.isDirectory(classes) || sources.equals(classes)) throw new IOException("Invalid build directories");
        var compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) throw new IOException("Build source digests require a JDK");
        var hashes = new HashMap<String, String>(); var duplicates = new HashSet<String>();
        long sourceBytes = 0;
        try (var paths = Files.walk(sources); var manager = compiler.getStandardFileManager(null, Locale.ROOT, StandardCharsets.UTF_8)) {
            var files = paths.filter(path -> path.toString().endsWith(".java") && safe(sources, path)).limit(2001).sorted().toList();
            if (files.size() > 2000) throw new IOException("Too many build source files");
            for (Path file : files) {
                byte[] bytes = bounded(file, 256 * 1024); sourceBytes += bytes.length;
                if (sourceBytes > 20 * 1024 * 1024) throw new IOException("Build source size exceeded");
                StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes));
                var diagnostics = new DiagnosticCollector<JavaFileObject>();
                var task = (JavacTask) compiler.getTask(new StringWriter(), manager, diagnostics, List.of("-proc:none", "--release", "21"), null,
                    manager.getJavaFileObjects(file));
                var units = task.parse();
                for (var unit : units) {
                    if (diagnostics.getDiagnostics().stream().anyMatch(value -> value.getKind() == Diagnostic.Kind.ERROR)) throw new IOException("Invalid build source syntax");
                    String key = (unit.getPackageName() == null ? "" : unit.getPackageName().toString() + ".") + file.getFileName();
                    if (hashes.putIfAbsent(key, digest(bytes)) != null) duplicates.add(key);
                }
            }
        }
        var entries = new TreeMap<String, String>();
        try (var paths = Files.walk(classes)) {
            var files = paths.filter(path -> path.toString().endsWith(".class") && safe(classes, path)).limit(10001).sorted().toList();
            if (files.size() > 10000) throw new IOException("Too many compiled classes");
            for (Path file : files) {
                byte[] bytes = bounded(file, MAX_CLASS_BYTES); var info = BuildClassFile.read(bytes);
                if (!FailureLocations.javaName(info.name(), 240, true) || info.sourceFile() == null) continue;
                int split = info.name().lastIndexOf('.');
                String key = (split < 0 ? "" : info.name().substring(0, split + 1)) + info.sourceFile();
                if (duplicates.contains(key) || !hashes.containsKey(key)) continue;
                if (entries.putIfAbsent(info.name(), hashes.get(key) + "," + digest(bytes)) != null) throw new IOException("Duplicate compiled class identity");
            }
        }
        var text = new StringBuilder("format=1\n");
        for (var entry : entries.entrySet()) text.append(escape(entry.getKey())).append('=').append(entry.getValue()).append('\n');
        byte[] bytes = text.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_MANIFEST_BYTES) throw new IOException("Build manifest size exceeded");
        Path output = classes.resolve(RESOURCE);
        if (!safeParents(classes, output) || Files.isSymbolicLink(output)) throw new IOException("Linked build output refused");
        Files.createDirectories(output.getParent());
        Path temporary = Files.createTempFile(output.getParent(), "source-digests-", ".tmp");
        try { Files.write(temporary, bytes); Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING); }
        finally { Files.deleteIfExists(temporary); }
        return entries.size();
    }
    static byte[] bounded(Path file, int limit) throws IOException {
        try (var stream = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = stream.readNBytes(limit + 1); if (bytes.length > limit) throw new IOException("Build file size exceeded"); return bytes;
        }
    }
    static String digest(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (Exception e) { throw new IllegalStateException("Cannot hash build data"); }
    }
    private static boolean safe(Path root, Path file) {
        try { return Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(file) && file.toRealPath().startsWith(root) && safeParents(root, file); }
        catch (Exception e) { return false; }
    }
    private static boolean safeParents(Path root, Path file) throws IOException {
        for (Path parent = file.getParent(); parent != null && !parent.equals(root); parent = parent.getParent())
            if (Files.exists(parent) && (Files.isSymbolicLink(parent) || !parent.toRealPath().equals(parent))) return false;
        return file.normalize().startsWith(root);
    }
    private static String escape(String name) {
        var result = new StringBuilder();
        for (char value : name.toCharArray()) {
            if (value >= 32 && value <= 126 && "=:# !\\".indexOf(value) < 0) result.append(value);
            else result.append("\\u%04x".formatted((int) value));
        }
        return result.toString();
    }
}
