package io.github.mochiuaena.triage.knowledge;

import static io.github.mochiuaena.triage.knowledge.KnowledgeModels.*;

import static org.springframework.http.HttpStatus.*;

import io.github.mochiuaena.triage.development.PrEvidenceRedactor;
import io.github.mochiuaena.triage.material.MaterialService;

import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Bounded passive UTF-8 reader. Never follows links or executes document instructions. */
@Component
public class KnowledgeFiles {
    private static final Set<String> EXCLUDED =
            Set.of(
                    ".git",
                    ".svn",
                    ".idea",
                    "node_modules",
                    "target",
                    "build",
                    "dist",
                    "out",
                    "logs",
                    "log",
                    ".gradle",
                    ".mvn",
                    "credentials",
                    "secrets",
                    ".ssh",
                    ".aws",
                    ".azure");

    public static String safePath(String input) {
        if (input == null
                || input.isBlank()
                || input.length() > 512
                || input.startsWith("/")
                || input.contains("\\")
                || input.contains(":")
                || input.codePoints().anyMatch(c -> c < 32 || c == 127))
            throw bad("文档相对路径无效（最多 512 字符）。");
        for (String p : input.split("/", -1))
            if (p.isEmpty() || p.equals(".") || p.equals("..")) throw bad("文档路径不能包含跳转。");
        return input;
    }

    public static boolean eligible(String path) {
        String p = path.toLowerCase(Locale.ROOT);
        return p.endsWith(".md") || p.endsWith(".txt");
    }

    public static boolean excluded(String path) {
        for (String part : path.toLowerCase(Locale.ROOT).split("/"))
            if (EXCLUDED.contains(part)
                    || part.startsWith(".env")
                    || part.contains("credential")
                    || part.contains("secret")
                    || part.equals("id_rsa")
                    || part.endsWith(".log")) return true;
        return false;
    }

    public Path canonical(String directory) {
        try {
            if (directory == null
                    || directory.isBlank()
                    || directory.length() > 2048
                    || directory.startsWith("\\\\")
                    || directory.startsWith("//")) throw bad("请选择本机文档目录，不能使用网络路径。");
            Path p = Path.of(directory);
            if (!p.isAbsolute()) throw bad("请选择绝对目录。");
            for (Path part : p)
                if (part.toString().equals("..") || part.toString().equals("."))
                    throw bad("目录不能包含跳转。");
            p = p.normalize();
            if (p.getParent() == null) throw bad("不能导入磁盘根目录。");
            Path cursor = p.getRoot();
            for (Path part : p) {
                cursor = cursor.resolve(part);
                if (linked(cursor)) throw bad("不能读取链接目录。");
            }
            if (!Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) throw bad("文档目录不存在。");
            Path real = p.toRealPath();
            if (!real.equals(p)) throw bad("目录包含链接或非规范路径。");
            return real;
        } catch (IOException | InvalidPathException e) {
            throw bad("文档目录不可访问。");
        }
    }

    public Read read(String directory, BooleanSupplier cancelled) {
        return read(directory, cancelled, count -> {});
    }

    public Read read(
            String directory, BooleanSupplier cancelled, java.util.function.IntConsumer progress) {
        Path root = canonical(directory);
        var docs = new ArrayList<Document>();
        var skips = new ArrayList<Exclusion>();
        int[] visited = {0}, bytes = {0}, chunks = {0}, eligible = {0}, readBytes = {0};
        boolean[] complete = {true};
        try {
            Files.walkFileTree(
                    root,
                    EnumSet.noneOf(FileVisitOption.class),
                    MAX_DEPTH + 1,
                    new SimpleFileVisitor<>() {
                        private String relative(Path path) {
                            return root.relativize(path).toString().replace('\\', '/');
                        }

                        private FileVisitResult entry(Path p) {
                            check(cancelled);
                            if (visited[0] >= MAX_ENTRIES) {
                                skip(skips, "[remaining]", "ENTRY_LIMIT");
                                complete[0] = false;
                                return FileVisitResult.TERMINATE;
                            }
                            visited[0]++;
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult preVisitDirectory(Path p, BasicFileAttributes a)
                                throws IOException {
                            if (p.equals(root)) return FileVisitResult.CONTINUE;
                            var check = entry(p);
                            if (check != FileVisitResult.CONTINUE) return check;
                            String path = relative(p);
                            if (linked(p) || excluded(path)) {
                                skip(skips, path, linked(p) ? "LINK" : "DIRECTORY");
                                return FileVisitResult.SKIP_SUBTREE;
                            }
                            if (root.relativize(p).getNameCount() > MAX_DEPTH) {
                                skip(skips, path, "DEPTH_LIMIT");
                                complete[0] = false;
                                return FileVisitResult.SKIP_SUBTREE;
                            }
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFile(Path p, BasicFileAttributes a)
                                throws IOException {
                            var check = entry(p);
                            if (check != FileVisitResult.CONTINUE) return check;
                            String path = relative(p);
                            if (a.isDirectory()) {
                                skip(skips, path, "DEPTH_LIMIT");
                                complete[0] = false;
                                return FileVisitResult.CONTINUE;
                            }
                            if (linked(p) || !a.isRegularFile()) {
                                skip(skips, path, "LINK_OR_SPECIAL");
                                return FileVisitResult.CONTINUE;
                            }
                            try {
                                safePath(path);
                            } catch (ResponseStatusException e) {
                                skip(skips, "[invalid path]", "PATH");
                                return FileVisitResult.CONTINUE;
                            }
                            if (excluded(path)) {
                                skip(skips, path, "DIRECTORY_OR_CREDENTIAL");
                                return FileVisitResult.CONTINUE;
                            }
                            if (!eligible(path)) {
                                skip(skips, path, "UNSUPPORTED");
                                return FileVisitResult.CONTINUE;
                            }
                            if (++eligible[0] > MAX_FILES) {
                                skip(skips, path, "FILE_LIMIT");
                                complete[0] = false;
                                return FileVisitResult.CONTINUE;
                            }
                            if (a.size() > MAX_FILE_BYTES) {
                                skip(skips, path, "FILE_BYTES");
                                complete[0] = false;
                                return FileVisitResult.CONTINUE;
                            }
                            if (readBytes[0] + a.size() > MAX_BYTES) {
                                skip(skips, path, "TOTAL_BYTES");
                                complete[0] = false;
                                return FileVisitResult.CONTINUE;
                            }
                            if (!p.toRealPath().startsWith(root)) throw bad("读取期间文件位置已变化，请重新预览。");
                            byte[] data;
                            try (var channel =
                                            Files.newByteChannel(
                                                    p,
                                                    Set.of(
                                                            StandardOpenOption.READ,
                                                            LinkOption.NOFOLLOW_LINKS));
                                    var in = java.nio.channels.Channels.newInputStream(channel)) {
                                data = in.readNBytes(MAX_FILE_BYTES + 1);
                            }
                            if (linked(p) || !p.toRealPath().startsWith(root))
                                throw bad("读取期间文件位置已变化，请重新预览。");
                            if (data.length > MAX_FILE_BYTES) {
                                skip(skips, path, "FILE_BYTES");
                                complete[0] = false;
                                return FileVisitResult.CONTINUE;
                            }
                            readBytes[0] += data.length;
                            if (readBytes[0] > MAX_BYTES) {
                                skip(skips, path, "TOTAL_BYTES");
                                complete[0] = false;
                                return FileVisitResult.CONTINUE;
                            }
                            Document document;
                            try {
                                document = document(path, data);
                            } catch (Rejected e) {
                                skip(skips, path, e.getMessage());
                                return FileVisitResult.CONTINUE;
                            }
                            int n = chunkCount(document.text());
                            if (bytes[0] + data.length > MAX_BYTES || chunks[0] + n > MAX_CHUNKS) {
                                skip(
                                        skips,
                                        path,
                                        bytes[0] + data.length > MAX_BYTES
                                                ? "TOTAL_BYTES"
                                                : "CHUNK_LIMIT");
                                complete[0] = false;
                                return FileVisitResult.CONTINUE;
                            }
                            docs.add(document);
                            progress.accept(docs.size());
                            bytes[0] += data.length;
                            chunks[0] += n;
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFileFailed(Path p, IOException e) {
                            check(cancelled);
                            skip(skips, relative(p), "UNREADABLE");
                            complete[0] = false;
                            return FileVisitResult.CONTINUE;
                        }
                    });
        } catch (IOException e) {
            throw bad("读取文档失败，请重新预览。");
        }
        canonical(directory);
        docs.sort(Comparator.comparing(Document::path));
        return new Read(
                root.toString(),
                null,
                List.copyOf(docs),
                List.copyOf(skips),
                visited[0],
                complete[0]);
    }

    private static boolean linked(Path path) throws IOException {
        return Files.isSymbolicLink(path)
                || !path.toAbsolutePath().normalize().equals(path.toRealPath())
                || Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS)
                        .isOther();
    }

    public static Document document(String path, byte[] bytes) {
        String text;
        try {
            text =
                    StandardCharsets.UTF_8
                            .newDecoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT)
                            .decode(ByteBuffer.wrap(bytes))
                            .toString();
        } catch (CharacterCodingException e) {
            throw new Rejected("INVALID_UTF8");
        }
        if (text.codePoints().anyMatch(c -> c == 0 || c < 9 || c > 13 && c < 32))
            throw new Rejected("BINARY");
        if (!PrEvidenceRedactor.clean(text).equals(text)
                || text.matches(
                        "(?s).*\\b(?:gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}|AKIA[A-Z0-9]{16}|sk-[A-Za-z0-9_-]{20,}).*"))
            throw new Rejected("CREDENTIAL");
        String title =
                text.lines()
                        .filter(s -> !s.isBlank())
                        .findFirst()
                        .orElse(path)
                        .replaceFirst("^#+\\s*", "");
        if (title.length() > 200) title = title.substring(0, 200);
        return new Document(path, hash(bytes), bytes.length, title, text);
    }

    public static int chunkCount(String text) {
        int start = 0, count = 0;
        do {
            int end = Math.min(text.length(), start + CHUNK_CHARS);
            if (end < text.length()
                    && end > start
                    && Character.isHighSurrogate(text.charAt(end - 1))) end--;
            count++;
            start = end;
        } while (start < text.length());
        return count;
    }

    public static List<Chunk> chunks(UUID version, String text) {
        var result = new ArrayList<Chunk>();
        int start = 0, line = 1, ordinal = 0;
        do {
            int end = Math.min(text.length(), start + CHUNK_CHARS);
            if (end < text.length()
                    && end > start
                    && Character.isHighSurrogate(text.charAt(end - 1))) end--;
            String part = text.substring(start, end);
            int startLine = line, endLine = line;
            for (int i = start; i < end; i++) {
                endLine = line;
                char character = text.charAt(i);
                // Keep both CRLF characters on their original line, even across chunks.
                if (character == '\n'
                        || character == '\r'
                                && (i + 1 == text.length() || text.charAt(i + 1) != '\n')) line++;
            }
            result.add(
                    new Chunk(
                            UUID.randomUUID(),
                            version,
                            ordinal++,
                            startLine,
                            endLine,
                            start,
                            end,
                            MaterialService.sha(part),
                            part));
            start = end;
        } while (start < text.length());
        return result;
    }

    public static void skip(List<Exclusion> list, String path, String reason) {
        if (list.size() < 100) list.add(new Exclusion(path, reason));
        else if (list.size() == 100) list.add(new Exclusion("[remaining]", "MORE_EXCLUSIONS"));
    }

    public static String hash(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static void check(BooleanSupplier cancelled) {
        if (Thread.currentThread().isInterrupted() || cancelled.getAsBoolean())
            throw new java.util.concurrent.CancellationException();
    }

    public static ResponseStatusException bad(String message) {
        return new ResponseStatusException(BAD_REQUEST, message);
    }

    public static final class Rejected extends RuntimeException {
        public Rejected(String reason) {
            super(reason);
        }
    }
}
