package io.github.mochiuaena.triage.source;

import io.github.mochiuaena.triage.execution.TriageEngine;
import io.github.mochiuaena.triage.execution.ExecutionSession;
import io.github.mochiuaena.triage.execution.RunFailure;
import io.github.mochiuaena.triage.tools.ServiceRegistry;
import io.github.mochiuaena.triage.domain.TriageModel.RequestDetails;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.Semaphore;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.*;
import static io.github.mochiuaena.triage.source.SourceModels.*;

@Service
public class SourceProjectService {
    private final SourceProjectRepository projects;
    private final JavaSourceIndexer indexer;
    private final ServiceRegistry registry;
    private final TriageEngine engines;
    private final Semaphore indexing = new Semaphore(1);
    public SourceProjectService(SourceProjectRepository projects, JavaSourceIndexer indexer, ServiceRegistry registry, TriageEngine engines) {
        this.projects = projects; this.indexer = indexer; this.registry = registry; this.engines = engines;
    }
    public List<View> list() { return projects.list().stream().map(this::view).toList(); }
    public View create(String name, String service, String directory) {
        registry.require(service);
        validateName(name);
        if (projects.list().size() >= 20) throw new ResponseStatusException(CONFLICT, "源码项目最多登记 20 个。");
        Path root = SourceFiles.root(directory);
        return view(projects.create(name.strip(), service, root.toString(), scan(root)));
    }
    public View update(UUID id, long revision, String name, String service, String directory) {
        var previous = projects.require(id);
        if (previous.revision() != revision) throw new ResponseStatusException(CONFLICT, "项目已更改，请刷新后重试。");
        validateName(name);
        String binding = service == null || service.isBlank() ? null : service.strip();
        if (binding != null) registry.require(binding);
        String normalized = directory == null ? "" : Path.of(directory).toAbsolutePath().normalize().toString();
        boolean moved = !previous.root().equals(normalized);
        String root = moved ? SourceFiles.root(directory).toString() : previous.root();
        if (previous.name().equals(name.strip()) && Objects.equals(previous.service(), binding) && !moved) return view(previous);
        return view(projects.update(previous, name.strip(), binding, root, moved ? scan(Path.of(root)) : previous.index()));
    }
    public void delete(UUID id, long revision) { projects.delete(id, revision); }
    private void validateName(String name) {
        if (name == null || name.isBlank() || name.length() > 80 || name.codePoints().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("Invalid source project name");
    }
    public View reindex(UUID id) {
        var project = projects.require(id);
        return view(projects.reindex(project, scan(SourceFiles.root(project.root()))));
    }
    private Index scan(Path root) {
        if (!indexing.tryAcquire()) throw new ResponseStatusException(TOO_MANY_REQUESTS, "已有源码索引在执行，请稍后重试。");
        try {
            Index index = indexer.index(root);
            if (index.files().isEmpty()) throw new ResponseStatusException(BAD_REQUEST, "没有可索引的 Java 文件，请检查目录、编码和排除范围。");
            return index;
        } finally { indexing.release(); }
    }
    public List<Excerpt> search(UUID id, String query) { return search(projects.require(id), query); }
    public List<Excerpt> search(Stored project, String query) {
        if (query == null || query.isBlank() || query.length() > 200) throw new IllegalArgumentException("Invalid source query");
        String[] words = query.toLowerCase(Locale.ROOT).split("[^a-z0-9_/$.\\p{IsHan}-]+");
        return project.index().files().stream().flatMap(file -> file.symbols().stream())
            .map(symbol -> Map.entry(symbol, score(symbol, words))).filter(value -> value.getValue() > 0)
            .sorted(Comparator.<Map.Entry<Symbol,Integer>>comparingInt(Map.Entry::getValue).reversed().thenComparing(value -> value.getKey().path()).thenComparingInt(value -> value.getKey().startLine()))
            .limit(5).map(value -> excerpt(project, value.getKey().id())).toList();
    }
    private int score(Symbol symbol, String[] words) {
        int total = 0;
        for (String word : words) {
            if (word.isBlank()) continue;
            if (symbol.className().toLowerCase(Locale.ROOT).contains(word)) total += 10;
            if (symbol.method().toLowerCase(Locale.ROOT).contains(word)) total += 12;
            if (!symbol.route().isBlank() && symbol.route().toLowerCase(Locale.ROOT).contains(word)) total += 20;
            if (symbol.calls().stream().anyMatch(call -> call.toLowerCase(Locale.ROOT).contains(word))) total += 8;
            if (symbol.path().toLowerCase(Locale.ROOT).contains(word)) total += 2;
        }
        if (!symbol.method().isBlank()) total++;
        return total == 1 ? 0 : total;
    }
    public Excerpt excerpt(UUID id, String symbol) { return excerpt(projects.require(id), symbol); }
    public Excerpt excerpt(Stored project, String identity) { return excerptAt(project, identity, null); }
    private Excerpt excerptAt(Stored project, String identity, Integer focus) {
        Symbol symbol = project.index().files().stream().flatMap(file -> file.symbols().stream()).filter(value -> value.id().equals(identity)).findFirst()
            .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "这个代码引用不属于当前项目索引。"));
        try {
            String text = SourceFiles.read(SourceFiles.root(project.root()), symbol.path());
            if (!SourceFiles.hash(text).equals(symbol.fileHash())) throw new java.io.IOException();
            List<String> lines = text.lines().toList();
            if (focus != null && (focus < symbol.startLine() || focus > symbol.endLine())) throw new java.io.IOException();
            int first = focus == null ? symbol.startLine() : Math.max(symbol.startLine(), focus - 2);
            int last = Math.min(symbol.endLine(), focus == null ? first + 79 : focus + 2);
            String content = String.join("\n", lines.subList(first - 1, Math.min(last, lines.size())));
            while (content.length() > 3600 && last > (focus == null ? first : focus)) { last--; content = String.join("\n", lines.subList(first - 1, last)); }
            while (content.length() > 3600 && focus != null && first < focus) { first++; content = String.join("\n", lines.subList(first - 1, last)); }
            if (content.length() > 3600) throw new java.io.IOException();
            return new Excerpt(symbol.id(), symbol.path(), symbol.fileHash(), symbol.className(), symbol.method(), symbol.route(), symbol.httpMethods(), symbol.calls(), first, last, content);
        } catch (Exception e) { throw new ResponseStatusException(CONFLICT, "源码文件已更改、不可读取或已被排除，请重新索引。"); }
    }
    public CallGraph chain(UUID id, String symbol) {
        Stored project = projects.require(id);
        excerpt(project, symbol); // Identity and file verification also apply to standalone graph requests.
        return new SourceCallGraph(project.index()).build(List.of(symbol), (identity, focus) -> excerptAt(project, identity, focus), () -> {});
    }
    private CallGraph graph(Stored project, List<Excerpt> candidates, List<EndpointMatch> matches, ExecutionSession session) {
        var failures = session.synthetic() ? List.<FailureMatch>of() : new SourceFailureLocations(project.index())
            .match(session.evidence(), (identity, focus) -> excerptAt(project, identity, focus), session::checkDeadline);
        boolean different = matches.stream().anyMatch(value -> "SOURCE_MISMATCH".equals(value.state()))
            || failures.stream().flatMap(value -> value.frames().stream()).anyMatch(value -> "SOURCE_MISMATCH".equals(value.state()));
        if (different) return new CallGraph("SOURCE_VERSION_DIFFERENT", "观测中有源码摘要与当前索引不同的类，未展开调用关系。请登记对应构建的源码再排查。",
            false, List.of(), List.of(), List.of(), List.of(), List.copyOf(matches), failures);
        List<String> roots = matches.isEmpty() ? candidates.stream().filter(value -> !value.method().isBlank() && !value.route().isBlank()).map(Excerpt::id).toList()
            : matches.stream().flatMap(value -> value.sourceIds().stream()).distinct().toList();
        if (roots.isEmpty() && matches.isEmpty()) roots = candidates.stream().filter(value -> !value.method().isBlank() && !value.method().equals("<init>") && !value.method().equals("main")).limit(2).map(Excerpt::id).toList();
        CallGraph graph = new SourceCallGraph(project.index()).build(roots, (identity, focus) -> excerptAt(project, identity, focus), session::checkDeadline);
        graph = new CallGraph(graph.state(), graph.message(), graph.truncated(), graph.rootIds(), graph.nodes(), graph.edges(), graph.evidenceLinks(), List.copyOf(matches), failures);
        return SourceEvidenceLinks.attach(graph, session.evidence(), session.synthetic());
    }
    private List<EndpointMatch> endpointMatches(Stored project, ExecutionSession session) {
        var details = session.evidence().stream().filter(value -> value.source().equals("read_service_metrics"))
            .map(value -> value.data().get("requestDetails")).filter(RequestDetails.class::isInstance).map(RequestDetails.class::cast).findFirst().orElse(null);
        if (details == null) return List.of();
        var resolver = new SourceCallGraph(project.index());
        return details.endpoints().stream().map(resolver::endpoint).toList();
    }
    public Stored bound(String service) { return projects.byService(service).orElse(null); }
    public Map<String,Object> summary(String service) {
        Stored project = bound(service);
        if (project == null) return Map.of("available", false);
        var selected = engines.snapshot();
        return Map.of("available", true, "id", project.id(), "name", project.name(), "revision", project.revision(),
            "modelSharing", authorized(project, selected), "model", project.model() == null ? "" : project.model());
    }
    public View sharing(UUID id, long revision, boolean enabled, UUID provider, Long providerVersion, String selection) {
        if (!enabled) return view(projects.share(id, revision, null, null, null, null));
        if (projects.require(id).service() == null) throw new ResponseStatusException(CONFLICT, "请先绑定服务，再确认模型读取授权。");
        var current = engines.snapshot(); var source = current.source();
        if (source == null || !"MODEL".equals(current.mode()) || !source.providerId().equals(provider) || !Objects.equals(source.version(), providerVersion)
                || !current.selectionToken().equals(selection)) throw new ResponseStatusException(CONFLICT, "当前模型服务或版本与确认内容不一致，请刷新后重试。");
        return view(projects.share(id, revision, source.providerId(), source.version(), current.modelName(), current.selectionToken()));
    }
    public boolean authorized(Stored project, TriageEngine selected) {
        if (project.service() == null || project.selection() == null || selected.source() == null) return false;
        Stored current = projects.require(project.id());
        return current.revision() == project.revision() && project.selection() != null && selected.source() != null
            && project.selection().equals(current.selection()) && current.selection().equals(selected.selectionToken())
            && engines.snapshot().selectionToken().equals(selected.selectionToken());
    }
    public Analysis queued(Stored project) {
        return analysis(project, "QUEUED", false, "等待运行观测，随后检索本机源码。", List.of());
    }
    public Analysis analyze(Stored project, ExecutionSession session, TriageEngine selected, boolean allowModel) {
        List<Excerpt> candidates = List.of();
        CallGraph graph = null;
        try {
            session.checkDeadline();
            if (projects.require(project.id()).revision() != project.revision()) return analysis(project, "STALE", false, "源码索引已变化，请重新排查。", List.of());
            StringBuilder query = new StringBuilder(session.question());
            for (var evidence : session.evidence()) {
                if (!evidence.source().equals("query_error_logs")) continue;
                if (evidence.data().get("entries") instanceof List<?> entries) for (var entry : entries)
                    if (entry instanceof Map<?,?> fields && fields.get("message") instanceof String message) query.append(' ').append(message);
            }
            if (session.evidence().stream().anyMatch(e -> "DATABASE_POOL".equals(e.data().get("observationType"))))
                query.insert(0, "getConnection query execute ");
            else if (session.evidence().stream().anyMatch(e -> e.data().get("timeoutCount") instanceof Number n && n.longValue() > 0))
                query.insert(0, "retrieve exchange send timeout ");
            List<EndpointMatch> matches = endpointMatches(project, session);
            candidates = matches.isEmpty() ? search(project, query.substring(0, Math.min(200, query.length())))
                : matches.stream().flatMap(value -> value.sourceIds().stream()).distinct().limit(5).map(id -> excerpt(project, id)).toList();
            boolean hasFailureLocations = !session.synthetic() && session.evidence().stream().anyMatch(value -> value.data().get("failureLocations") instanceof List<?> locations && !locations.isEmpty());
            if (candidates.isEmpty() && matches.isEmpty() && !hasFailureLocations) return analysis(project, "NO_MATCH", false, "未找到匹配的类、方法或接口。可以在源码页面用方法名或接口路径检索。", List.of());
            graph = graph(project, candidates, matches, session);
            session.recordSourceGraph(graph);
            if ("SOURCE_VERSION_DIFFERENT".equals(graph.state())) return analysis(project, "SOURCE_VERSION_DIFFERENT", false,
                "运行构建与当前源码不同，已保留运行诊断；未展开调用关系或请求源码模型检查。", List.of(), graph);
            if (candidates.isEmpty()) {
                boolean located = graph.failureMatches().stream().flatMap(value -> value.frames().stream()).anyMatch(value -> !value.excerpts().isEmpty());
                return analysis(project, located ? "LOCAL" : "NO_MATCH", false,
                    located ? "已按错误位置匹配本机源码。新增位置与片段仅在本机展示。" : "已读取观测位置，但当前源码没有对应入口或有效位置。请检查目录或重新索引。", List.of(), graph);
            }
            if (!allowModel || !authorized(project, selected)) return analysis(project, "LOCAL", false, "已检索本机代码，未向模型发送源码。引用仅供核查，不能证明本次请求的执行路径。", candidates, graph);
            if (session.evidence().stream().noneMatch(value -> value.source().equals("read_service_metrics") && value.data().get("requestCount") instanceof Number count && count.longValue() > 0))
                return analysis(project, "LOCAL", false, "缺少可用运行观测，保留本机源码检索结果，未请求源码模型检查。", candidates, graph);
            List<Excerpt> local = candidates;
            List<String> ids = selected.selectSources(session, () -> {
                if (!authorized(project, selected)) throw new RunFailure("SOURCE_PERMISSION_CHANGED", "源码授权已变化。");
                return local.stream().map(value -> excerpt(project, value.id())).toList();
            });
            if (ids == null) return analysis(project, "LOCAL", false, "模型剩余轮次或时长不足，保留本机检索结果。", candidates, graph);
            if (!authorized(project, selected)) return analysis(project, "MODEL_REJECTED", true, "源码授权已变化，未采用模型返回的引用。", candidates, graph);
            Set<String> allowed = new HashSet<>(candidates.stream().map(Excerpt::id).toList());
            if (ids.size() > 3 || new HashSet<>(ids).size() != ids.size() || !allowed.containsAll(ids))
                throw new RunFailure("INVALID_SOURCE_SELECTION", "模型代码引用无效。");
            List<Excerpt> chosen = ids.stream().map(id -> excerpt(project, id)).toList();
            return analysis(project, ids.isEmpty() ? "MODEL_NO_MATCH" : "MODEL_SELECTED", true,
                ids.isEmpty() ? "模型未选中候选引用，保留本机检索结果。" : "模型从本机候选中选出代码引用。仍需核对调用路径和配置，不能据此确认根因。", ids.isEmpty() ? candidates : chosen, graph);
        } catch (RunFailure e) {
            if (Set.of("RUN_CANCELLED", "RUN_INTERRUPTED", "RUN_TIMEOUT").contains(e.code())) throw e;
            return analysis(project, e.code().equals("INVALID_SOURCE_SELECTION") ? "MODEL_REJECTED" : "MODEL_UNAVAILABLE", session.sourceModelDispatched(),
                e.code().equals("INVALID_SOURCE_SELECTION") ? "模型返回的代码引用无效，保留本机检索结果和运行判断。" : "源码模型检查未完成，保留本机检索结果和运行判断。", candidates, graph);
        } catch (Exception e) {
            return analysis(project, "STALE", session.sourceModelDispatched(), "源码文件、索引或授权不可用，请检查后重新索引。运行判断已保留。", List.of(), graph);
        }
    }
    private Analysis analysis(Stored project, String state, boolean modelUsed, String message, List<Excerpt> excerpts) {
        return analysis(project, state, modelUsed, message, excerpts, null);
    }
    private Analysis analysis(Stored project, String state, boolean modelUsed, String message, List<Excerpt> excerpts, CallGraph graph) {
        return new Analysis(project.id(), project.name(), project.revision(), project.index().hash(), state, modelUsed, message, List.copyOf(excerpts), graph);
    }
    public Map<String,Object> disclosure() {
        var engine = engines.snapshot(); var source = engine.source();
        return Map.of("available", source != null && "MODEL".equals(engine.mode()), "model", engine.modelName() == null ? "" : engine.modelName(),
            "provider", source == null ? "" : source.displayName(), "providerId", source == null ? "" : source.providerId().toString(),
            "providerVersion", source == null ? 0L : source.version(), "selection", engine.selectionToken());
    }
    private View view(Stored value) {
        return new View(value.id(), value.name(), value.service(), value.root(), value.revision(), value.index().hash(), value.index().indexedAt(),
            value.index().files().size(), value.index().files().stream().mapToInt(file -> file.symbols().size()).sum(), value.index().skippedFiles(), value.index().parseFailures(),
            value.selection() != null, value.providerId(), value.model(), authorized(value, engines.snapshot()));
    }
}
