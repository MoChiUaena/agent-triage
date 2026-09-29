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
                    checkDeadline();
                    if (!path.equals(root) && (EXCLUDED.contains(path.getFileName().toString()) || Files.isSymbolicLink(path)
                            || !path.toRealPath().equals(path) || !path.toRealPath().startsWith(root))) return FileVisitResult.SKIP_SUBTREE;
                    return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult visitFile(Path path, BasicFileAttributes attrs) {
                    checkDeadline();
                    if (!path.getFileName().toString().endsWith(".java")) return FileVisitResult.CONTINUE;
                    if (++counts[0] > 2000) throw new IllegalArgumentException("项目超过 2000 个 Java 文件，请登记较小的模块目录。");
                    String relative = root.relativize(path).toString().replace('\\', '/');
                    try {
                        String text = SourceFiles.read(root, relative); totalBytes[0] += text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
                        if (totalBytes[0] > 20 * 1024 * 1024) throw new IllegalArgumentException("源码超过 20 MB，请登记较小的模块目录。");
                        String hash = SourceFiles.hash(text);
                        Parsed parsed = parse(compiler, relative, hash, text);
                        if (parsed == null) counts[2]++; else {
                            counts[3] += parsed.symbols().size();
                            if (counts[3] > 10000) throw new IllegalArgumentException("项目超过 10000 个源码符号，请登记较小的模块目录。");
                            files.add(new FileEntry(relative, hash, parsed.symbols(), parsed.types()));
                        }
                    } catch (java.io.IOException e) { counts[1]++; }
                    return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult visitFileFailed(Path path, java.io.IOException error) { counts[1]++; return FileVisitResult.CONTINUE; }
                private void checkDeadline() {
                    if (Thread.currentThread().isInterrupted() || System.nanoTime() >= deadline) throw new IllegalArgumentException("源码索引超过时限，请登记较小的模块目录。");
                }
            });
        } catch (java.io.IOException e) { throw new IllegalArgumentException("项目目录读取失败，请检查文件权限。"); }
        files.sort(Comparator.comparing(FileEntry::path));
        String aggregate = files.stream().map(file -> file.path() + ":" + file.hash()).collect(java.util.stream.Collectors.joining("\n"));
        return new Index(SourceFiles.hash(aggregate), Instant.now(), counts[0], counts[1], counts[2], List.copyOf(files), 2);
    }
    private record Parsed(List<Symbol> symbols, List<TypeInfo> types) {}
    private Parsed parse(JavaCompiler compiler, String path, String hash, String text) throws java.io.IOException {
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        try (var manager = compiler.getStandardFileManager(diagnostics, Locale.ROOT, java.nio.charset.StandardCharsets.UTF_8)) {
            URI uri; try { uri = new URI("string", null, "/" + path, null); } catch (Exception e) { throw new java.io.IOException(); }
            var source = new SimpleJavaFileObject(uri, JavaFileObject.Kind.SOURCE) { @Override public CharSequence getCharContent(boolean ignored) { return text; } };
            var task = (JavacTask) compiler.getTask(null, manager, diagnostics, List.of("-proc:none", "-source", "21"), null, List.of(source));
            var units = task.parse(); var result = new ArrayList<Symbol>(); var types = new ArrayList<TypeInfo>(); var positions = Trees.instance(task).getSourcePositions();
            for (CompilationUnitTree unit : units) {
                new TreeScanner<Void, Void>() {
                    String type = ""; String prefix = "";
                    TypeInfo owner;
                    @Override public Void visitClass(ClassTree node, Void unused) {
                        String oldType = type, oldPrefix = prefix;
                        TypeInfo oldOwner = owner;
                        if (node.getSimpleName().length() == 0) return null;
                        type = (oldType.isEmpty() ? unit.getPackageName() == null ? "" : unit.getPackageName() + "." : oldType + ".") + node.getSimpleName();
                        prefix = mapping(node.getModifiers().getAnnotations()) ? route(node.getModifiers().getAnnotations()) : "";
                        owner = JavaCallMetadata.type(unit, node, type); types.add(owner);
                        add(node, "", node.getSimpleName().toString(), prefix == null ? "" : prefix, List.of(), List.of(), null);
                        super.visitClass(node, unused); type = oldType; prefix = oldPrefix; owner = oldOwner; return null;
                    }
                    @Override public Void visitMethod(MethodTree node, Void unused) {
                        MethodInfo details = JavaCallMetadata.method(unit, node, owner, positions);
                        String route = route(node.getModifiers().getAnnotations());
                        List<String> verbs = verbs(node.getModifiers().getAnnotations());
                        add(node, node.getName().toString(), node.getName() + "(" + node.getParameters().stream().map(p -> p.getType().toString()).collect(java.util.stream.Collectors.joining(",")) + ")",
                            route == null || prefix == null ? "" : (prefix + "/" + route).replaceAll("/+", "/"), verbs, details.invocations().stream().map(Invocation::expression).distinct().toList(), details);
                        return super.visitMethod(node, unused);
                    }
                    void add(Tree node, String method, String signature, String route, List<String> verbs, List<String> calls, MethodInfo details) {
                        long start = positions.getStartPosition(unit, node), end = positions.getEndPosition(unit, node);
                        if (start < 0 || end <= start || result.size() >= 1000) return;
                        int first = (int) unit.getLineMap().getLineNumber(start), last = (int) unit.getLineMap().getLineNumber(end - 1);
                        String id = "SRC-" + SourceFiles.hash(path + ":" + hash + ":" + first + ":" + last + ":" + signature).substring(0, 24);
                        result.add(new Symbol(id, path, hash, type, method, signature, route, verbs, calls, first, last, details));
                    }
                }.scan(unit, null);
            }
            if (diagnostics.getDiagnostics().stream().anyMatch(d -> d.getKind() == Diagnostic.Kind.ERROR)) return null;
            return new Parsed(List.copyOf(result), List.copyOf(types));
        }
    }
    private static String simple(String text) { return text.substring(text.lastIndexOf('.') + 1); }
    private static final Set<String> MAPPINGS = Set.of("RequestMapping", "GetMapping", "PostMapping", "PutMapping", "PatchMapping", "DeleteMapping");
    private static boolean mapping(List<? extends AnnotationTree> annotations) {
        return annotations.stream().anyMatch(a -> MAPPINGS.contains(simple(a.getAnnotationType().toString())));
    }
    private static List<String> verbs(List<? extends AnnotationTree> annotations) {
        var result = new LinkedHashSet<String>();
        for (var annotation : annotations) {
            String name = simple(annotation.getAnnotationType().toString());
            if (!MAPPINGS.contains(name)) continue;
            if (!name.equals("RequestMapping")) result.add(name.substring(0, name.length() - 7).toUpperCase(Locale.ROOT));
            else for (var argument : annotation.getArguments()) if (argument instanceof AssignmentTree assignment && assignment.getVariable().toString().equals("method")) {
                var value = assignment.getExpression();
                List<? extends ExpressionTree> values = value instanceof NewArrayTree array && array.getInitializers() != null ? array.getInitializers() : List.of(value);
                for (var verb : values) {
                    String text = simple(verb.toString());
                    if (Set.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS", "TRACE").contains(text)) result.add(text);
                }
            }
        }
        return List.copyOf(result);
    }
    private static String route(List<? extends AnnotationTree> annotations) {
        for (var annotation : annotations) {
            if (!MAPPINGS.contains(simple(annotation.getAnnotationType().toString()))) continue;
            for (var argument : annotation.getArguments()) {
                ExpressionTree value = argument;
                if (argument instanceof AssignmentTree assignment) {
                    if (!List.of("value", "path").contains(assignment.getVariable().toString())) continue;
                    value = assignment.getExpression();
                }
                if (value instanceof LiteralTree literal && literal.getValue() instanceof String text) return text;
                if (value instanceof NewArrayTree array && array.getInitializers() != null && !array.getInitializers().isEmpty()
                        && array.getInitializers().getFirst() instanceof LiteralTree literal && literal.getValue() instanceof String text) return text;
                return null; // A constant, concatenation or other dynamic expression is not a resolved route.
            }
            return "";
        }
        return null;
    }
}
