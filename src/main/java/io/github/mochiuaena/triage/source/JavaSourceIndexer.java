package io.github.mochiuaena.triage.source;

import com.sun.source.tree.*;
import com.sun.source.util.*;
import javax.tools.*;
import java.net.URI;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Component;
import static io.github.mochiuaena.triage.source.SourceModels.*;

/** Syntax-only Java 21 index: never loads a project class or annotation processor. */
@Component
public class JavaSourceIndexer {
    private static final Set<String> EXCLUDED = Set.of(".git", ".idea", ".gradle", "target", "build", "data", "logs", "node_modules", "dist", ".env");
    public Index index(Path root) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) throw new IllegalStateException("源码索引需要包含 Java 编译工具的 JDK 21。");
        var files = new ArrayList<FileEntry>(); int[] counts = new int[4]; long[] totalBytes = {0};
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override public FileVisitResult preVisitDirectory(Path path, BasicFileAttributes attrs) throws java.io.IOException {
                    if (!path.equals(root) && (EXCLUDED.contains(path.getFileName().toString()) || Files.isSymbolicLink(path)
                            || !path.toRealPath().equals(path) || !path.toRealPath().startsWith(root))) return FileVisitResult.SKIP_SUBTREE;
                    return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult visitFile(Path path, BasicFileAttributes attrs) {
                    if (Thread.currentThread().isInterrupted() || System.nanoTime() >= deadline) throw new IllegalArgumentException("源码索引超过时限，请登记较小的模块目录。");
                    if (!path.getFileName().toString().endsWith(".java")) return FileVisitResult.CONTINUE;
                    if (++counts[0] > 2000) throw new IllegalArgumentException("项目超过 2000 个 Java 文件，请登记较小的模块目录。");
                    String relative = root.relativize(path).toString().replace('\\', '/');
                    try {
                        String text = SourceFiles.read(root, relative); totalBytes[0] += text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
                        if (totalBytes[0] > 20 * 1024 * 1024) throw new IllegalArgumentException("源码超过 20 MB，请登记较小的模块目录。");
                        String hash = SourceFiles.hash(text);
                        List<Symbol> symbols = parse(compiler, relative, hash, text);
                        if (symbols == null) counts[2]++; else {
                            counts[3] += symbols.size();
                            if (counts[3] > 10000) throw new IllegalArgumentException("项目超过 10000 个源码符号，请登记较小的模块目录。");
                            files.add(new FileEntry(relative, hash, symbols));
                        }
                    } catch (java.io.IOException e) { counts[1]++; }
                    return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult visitFileFailed(Path path, java.io.IOException error) { counts[1]++; return FileVisitResult.CONTINUE; }
            });
        } catch (java.io.IOException e) { throw new IllegalArgumentException("项目目录读取失败，请检查文件权限。"); }
        files.sort(Comparator.comparing(FileEntry::path));
        String aggregate = files.stream().map(file -> file.path() + ":" + file.hash()).collect(java.util.stream.Collectors.joining("\n"));
        return new Index(SourceFiles.hash(aggregate), Instant.now(), counts[0], counts[1], counts[2], List.copyOf(files));
    }
    private List<Symbol> parse(JavaCompiler compiler, String path, String hash, String text) throws java.io.IOException {
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        try (var manager = compiler.getStandardFileManager(diagnostics, Locale.ROOT, java.nio.charset.StandardCharsets.UTF_8)) {
            URI uri; try { uri = new URI("string", null, "/" + path, null); } catch (Exception e) { throw new java.io.IOException(); }
            var source = new SimpleJavaFileObject(uri, JavaFileObject.Kind.SOURCE) { @Override public CharSequence getCharContent(boolean ignored) { return text; } };
            var task = (JavacTask) compiler.getTask(null, manager, diagnostics, List.of("-proc:none", "-source", "21"), null, List.of(source));
            var units = task.parse(); var result = new ArrayList<Symbol>(); var positions = Trees.instance(task).getSourcePositions();
            for (CompilationUnitTree unit : units) {
                new TreeScanner<Void, Void>() {
                    String type = ""; String prefix = "";
                    @Override public Void visitClass(ClassTree node, Void unused) {
                        String oldType = type, oldPrefix = prefix;
                        if (node.getSimpleName().length() == 0) return null;
                        type = (oldType.isEmpty() ? unit.getPackageName() == null ? "" : unit.getPackageName() + "." : oldType + ".") + node.getSimpleName();
                        prefix = route(node.getModifiers().getAnnotations());
                        add(node, "", node.getSimpleName().toString(), prefix, List.of(), List.of());
                        super.visitClass(node, unused); type = oldType; prefix = oldPrefix; return null;
                    }
                    @Override public Void visitMethod(MethodTree node, Void unused) {
                        var calls = new LinkedHashSet<String>();
                        new TreeScanner<Void, Void>() { @Override public Void visitMethodInvocation(MethodInvocationTree call, Void value) {
                            calls.add(call.getMethodSelect().toString()); return super.visitMethodInvocation(call, value);
                        }}.scan(node.getBody(), null);
                        String route = route(node.getModifiers().getAnnotations());
                        List<String> verbs = node.getModifiers().getAnnotations().stream().map(a -> simple(a.getAnnotationType().toString()))
                            .filter(a -> a.endsWith("Mapping") && !a.equals("RequestMapping")).map(a -> a.substring(0, a.length() - 7).toUpperCase(Locale.ROOT)).toList();
                        add(node, node.getName().toString(), node.getName() + "(" + node.getParameters().stream().map(p -> p.getType().toString()).collect(java.util.stream.Collectors.joining(",")) + ")",
                            route.isEmpty() && verbs.isEmpty() ? "" : (prefix + "/" + route).replaceAll("/+", "/"), verbs, calls.stream().limit(30).toList());
                        return super.visitMethod(node, unused);
                    }
                    void add(Tree node, String method, String signature, String route, List<String> verbs, List<String> calls) {
                        long start = positions.getStartPosition(unit, node), end = positions.getEndPosition(unit, node);
                        if (start < 0 || end <= start || result.size() >= 1000) return;
                        int first = (int) unit.getLineMap().getLineNumber(start), last = (int) unit.getLineMap().getLineNumber(end - 1);
                        String id = "SRC-" + SourceFiles.hash(path + ":" + hash + ":" + first + ":" + last + ":" + signature).substring(0, 24);
                        result.add(new Symbol(id, path, hash, type, method, signature, route, verbs, calls, first, last));
                    }
                }.scan(unit, null);
            }
            if (diagnostics.getDiagnostics().stream().anyMatch(d -> d.getKind() == Diagnostic.Kind.ERROR)) return null;
            return List.copyOf(result);
        }
    }
    private static String simple(String text) { return text.substring(text.lastIndexOf('.') + 1); }
    private static String route(List<? extends AnnotationTree> annotations) {
        for (var annotation : annotations) {
            if (!simple(annotation.getAnnotationType().toString()).endsWith("Mapping")) continue;
            for (var argument : annotation.getArguments()) {
                ExpressionTree value = argument;
                if (argument instanceof AssignmentTree assignment) {
                    if (!List.of("value", "path").contains(assignment.getVariable().toString())) continue;
                    value = assignment.getExpression();
                }
                if (value instanceof LiteralTree literal && literal.getValue() instanceof String text) return text;
                if (value instanceof NewArrayTree array && array.getInitializers() != null && !array.getInitializers().isEmpty()
                        && array.getInitializers().getFirst() instanceof LiteralTree literal && literal.getValue() instanceof String text) return text;
            }
        }
        return "";
    }
}
