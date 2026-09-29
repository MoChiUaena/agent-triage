package io.github.mochiuaena.triage.source;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.regex.Pattern;

final class SourceFiles {
    static final int MAX_FILE_BYTES = 256 * 1024;
    private static final Pattern CREDENTIAL = Pattern.compile("(?is)(?:sk-[a-z0-9_-]{24,}|gh[pousr]_[a-z0-9]{30,}|github_pat_[a-z0-9_]{30,}|-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----|(?:api[_-]?key|secret|password|authorization|(?:access[_-]?)?token)\\s*=\\s*\"(?!\\$\\{|test-only|fixture)[^\"\\r\\n]{4,}\"|\\.header\\s*\\(\\s*\"(?:Authorization|X-API-Key)\"\\s*,\\s*\"[^\"]{8,}\")");
    static Path root(String text) {
        try {
            if (text == null || text.isBlank() || text.length() > 2048 || text.startsWith("\\\\") || text.startsWith("//")) throw new IllegalArgumentException();
            Path path = Path.of(text).toAbsolutePath().normalize().toRealPath();
            if (!Files.isDirectory(path) || path.getParent() == null) throw new IllegalArgumentException();
            return path;
        } catch (IOException | RuntimeException e) { throw new IllegalArgumentException("项目目录不可读取，请选择本机项目文件夹。"); }
    }
    static String read(Path root, String relative) throws IOException {
        if (relative == null || !relative.endsWith(".java") || relative.contains("\\") || relative.startsWith("/") || relative.contains("..")) throw new IOException("Invalid source path");
        Path path = root.resolve(relative).normalize();
        if (!path.startsWith(root) || Files.isSymbolicLink(path) || !path.toRealPath().startsWith(root) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Source path left the project");
        for (Path parent = path.getParent(); parent != null && !parent.equals(root); parent = parent.getParent())
            if (Files.isSymbolicLink(parent) || !parent.toRealPath().equals(parent)) throw new IOException("Linked source directory refused");
        if (Files.size(path) > MAX_FILE_BYTES) throw new IOException("Source file too large");
        try (var channel = FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            ByteBuffer bytes = ByteBuffer.allocate(MAX_FILE_BYTES + 1);
            while (channel.read(bytes) > 0 && bytes.hasRemaining()) { }
            if (bytes.position() > MAX_FILE_BYTES || !path.toRealPath().startsWith(root)) throw new IOException("Source file changed or too large");
            bytes.flip();
            String value = StandardCharsets.UTF_8.newDecoder().decode(bytes).toString();
            if (CREDENTIAL.matcher(value).find()) throw new IOException("Credential-bearing source excluded");
            return value;
        }
    }
    static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException("Cannot hash source"); }
    }
}
