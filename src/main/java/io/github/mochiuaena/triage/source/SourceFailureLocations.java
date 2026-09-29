package io.github.mochiuaena.triage.source;

import io.github.mochiuaena.triage.domain.TriageModel.*;
import java.util.*;
import java.util.function.BiFunction;
import org.springframework.web.server.ResponseStatusException;
import static io.github.mochiuaena.triage.source.SourceModels.*;

/** A source correspondence, not proof that the registered files produced the running bytecode. */
final class SourceFailureLocations {
    private final List<Symbol> symbols;
    SourceFailureLocations(Index index) { symbols = index.files().stream().flatMap(file -> file.symbols().stream()).toList(); }
    List<FailureMatch> match(List<Evidence> evidence, BiFunction<String, Integer, Excerpt> reader, Runnable deadline) {
        var result = new ArrayList<FailureMatch>();
        for (var entry : evidence) {
            if (!entry.source().equals("query_error_logs") || !(entry.data().get("failureLocations") instanceof List<?> locations)) continue;
            for (var item : locations) {
                if (result.size() == 3) return List.copyOf(result);
                if (!(item instanceof RequestFailure failure)) continue;
                var frames = new ArrayList<FrameMatch>();
                for (var frame : failure.location().frames()) { deadline.run(); frames.add(frame(frame, reader)); }
                result.add(new FailureMatch(entry.id(), failure.timestamp(), failure.traceId(), failure.location().kind(),
                    failure.location().exceptionTypes(), Boolean.TRUE.equals(failure.location().truncated()), List.copyOf(frames)));
            }
        }
        return List.copyOf(result);
    }
    private FrameMatch frame(FailureFrame frame, BiFunction<String, Integer, Excerpt> reader) {
        var named = named(frame.className(), frame.methodName());
        if (named.isEmpty() && frame.className().contains("$")) named = named(frame.className().replace('$', '.'), frame.methodName());
        if (named.isEmpty()) return result(frame, "UNMATCHED", "当前索引没有对应的类和方法，可能位于索引外或来自其他运行版本。", List.of());
        var files = named.stream().filter(symbol -> frame.fileName() == null || filename(symbol.path()).equals(frame.fileName())).toList();
        if (files.isEmpty()) return result(frame, "FILE_MISMATCH", "类和方法同名，但文件名不同，未绑定到源码。", List.of());
        var matches = files.stream().filter(symbol -> frame.lineNumber() == null || frame.lineNumber() >= symbol.startLine() && frame.lineNumber() <= symbol.endLine()).toList();
        if (matches.isEmpty()) return result(frame, "LINE_MISMATCH", "观测行号不在当前方法范围内，请核对运行版本并重新索引。", List.of());
        var excerpts = new ArrayList<Excerpt>();
        boolean stale = false;
        for (var symbol : matches.stream().limit(2).toList()) {
            try { excerpts.add(reader.apply(symbol.id(), frame.lineNumber())); }
            catch (ResponseStatusException e) { stale = true; }
        }
        if (stale) return result(frame, "STALE", "候选文件已修改或不可读取，保留可验证的片段，请重新索引。", excerpts);
        if (matches.size() > 1) return result(frame, "AMBIGUOUS", "多个源码方法符合位置，仅展示前两个候选，尚未确定所属模块。", excerpts);
        if (frame.lineNumber() == null) return result(frame, "CANDIDATE", "观测没有行号，只匹配到方法候选。", excerpts);
        return result(frame, "LINE_MATCH", (frame.fileName() == null ? "类、方法及行号对应，观测未提供文件名；" : "类、方法、文件与行号对应；")
            + "源码版本是否与运行版本一致仍需核对。", excerpts);
    }
    private List<Symbol> named(String className, String method) {
        return symbols.stream().filter(symbol -> symbol.className().equals(className) && symbol.method().equals(method))
            .sorted(Comparator.comparing(Symbol::path).thenComparingInt(Symbol::startLine)).toList();
    }
    private String filename(String path) { String normalized = path.replace('\\', '/'); return normalized.substring(normalized.lastIndexOf('/') + 1); }
    private FrameMatch result(FailureFrame frame, String state, String message, List<Excerpt> excerpts) {
        return new FrameMatch(frame, state, message, List.copyOf(excerpts));
    }
}
