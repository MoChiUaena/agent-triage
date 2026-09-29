package io.github.mochiuaena.triage.source;

import io.github.mochiuaena.triage.domain.TriageModel.*;
import io.github.mochiuaena.triage.tools.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.*;
import static io.github.mochiuaena.triage.source.SourceModels.*;

/** Read-only service and source preflight. Does not create runs, call models or return code. */
@Service
public class SourceReadinessService {
    public record Step(String id, String title, String state, String message, String nextAction) {}
    public record Project(UUID id, String name, long revision, int files, int symbols) {}
    public record Position(FailureFrame frame, String state, String message, VersionCheck version) {}
    public record Check(ServiceRegistry.View service, Instant checkedAt, int windowMinutes, long responseMillis, String state,
                        boolean observationsAvailable, Integer requestCount, Project project, List<Step> steps,
                        List<EndpointMatch> endpoints, List<Position> errorPositions) {}
    private final ServiceRegistry registry;
    private final ObservationSource observation;
    private final LiveObservationClient live;
    private final SourceProjectRepository projects;
    private final SourceProjectService sources;
    private final Semaphore capacity = new Semaphore(2);
    public SourceReadinessService(ServiceRegistry registry, ObservationSource observation, LiveObservationClient live,
                                  SourceProjectRepository projects, SourceProjectService sources) {
        this.registry = registry; this.observation = observation; this.live = live; this.projects = projects; this.sources = sources;
    }
    public Check check(String service, Integer minutes) {
        var target = registry.require(service);
        int window = minutes == null ? Math.min(5, target.maxWindowMinutes()) : minutes;
        if (window < 1 || window > target.maxWindowMinutes()) throw new IllegalArgumentException("Invalid integration window");
        if (!capacity.tryAcquire()) throw new ResponseStatusException(TOO_MANY_REQUESTS, "接入检查正在处理其他请求，请稍后重试。");
        long started = System.nanoTime();
        try { return inspect(target, window, started); } finally { capacity.release(); }
    }
    private Check inspect(ServiceRegistry.Target target, int window, long started) {
        String service = target.info().id();
        var view = registry.views().stream().filter(value -> value.id().equals(service)).findFirst().orElseThrow();
        Stored project = projects.byService(service).orElse(null);
        var steps = new ArrayList<Step>(); var entries = new ArrayList<EndpointMatch>(); var positions = new ArrayList<Position>();
        LiveObservationClient.Snapshot snapshot = null;
        String state = "PARTIAL";
        if (observation.synthetic()) {
            steps.add(step("OBSERVATIONS", "服务观测", "WAIT", "当前使用合成演示数据。", "通过启动配置切换到 LIVE 并登记业务服务。"));
            state = "SYNTHETIC";
        } else {
            try {
                var scenario = live.scenario(target);
                snapshot = live.snapshot(new ToolContext(service, window, scenario, Instant.now(), target));
                steps.add(step("OBSERVATIONS", "服务观测", "PASS", "观测接口可读取，身份、窗口和协议校验通过。", ""));
            } catch (ObservationFailure e) {
                steps.add(step("OBSERVATIONS", "服务观测", "BLOCKED", e.getMessage(), "核对业务服务是否启动、观测协议与启动配置。"));
                state = "OBSERVATION_UNAVAILABLE";
            } catch (RuntimeException e) {
                steps.add(step("OBSERVATIONS", "服务观测", "BLOCKED", "观测读取未完成。", "核对服务配置后重试。"));
                state = "OBSERVATION_UNAVAILABLE";
            }
        }
        steps.add(snapshot == null ? step("TRAFFIC", "窗口请求", "SKIPPED", "尚未读取实际观测。", "先完成服务观测接入。")
            : snapshot.requestCount() == 0 ? step("TRAFFIC", "窗口请求", "WAIT", "本窗口没有业务请求。", "自行访问业务接口后重新检查，或扩大检查窗口。")
            : step("TRAFFIC", "窗口请求", "PASS", "本窗口记录了 " + snapshot.requestCount() + (target.protocol() == ServiceRegistry.Protocol.DATABASE_V2 ? " 次数据库观测操作。" : " 次请求。"), ""));
        steps.add(project == null ? step("SOURCE", "本机源码", "WAIT", "这个服务尚未绑定源码项目。", "在项目源码页登记并绑定本机目录。")
            : step("SOURCE", "本机源码", "PASS", "已登记 " + project.index().files().size() + " 个文件，索引 v" + project.revision() + "。", ""));
        if (snapshot == null || project == null) {
            steps.add(step("ENTRIES", "代码入口与位置", "SKIPPED", "需要可读取的观测和已绑定源码。", "先完成前面的接入步骤。"));
            steps.add(step("BUILD", "构建源码摘要", "SKIPPED", "尚未取得可核对的代码位置。", "先完成观测与源码绑定。"));
        } else if (snapshot.requestDetails() == null) {
            steps.add(step("ENTRIES", "代码入口与位置", "OPTIONAL", "当前协议不提供 MVC 入口信息，仍可按关键词检索源码。", "需要按接口定位时，使用 HTTP V3 并开启 endpoint-observations。"));
            steps.add(step("BUILD", "构建源码摘要", "OPTIONAL", "当前协议不提供构建源码摘要。", "需要版本核对时，使用 HTTP V3 和构建清单。"));
        } else {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            Runnable remaining = () -> { if (Thread.currentThread().isInterrupted() || System.nanoTime() > deadline)
                throw new ResponseStatusException(SERVICE_UNAVAILABLE, "本机源码检查超过时限，请缩小项目或稍后重试。"); };
            var resolver = new SourceCallGraph(project.index());
            for (var summary : snapshot.requestDetails().endpoints()) {
                remaining.run(); var match = resolver.endpoint(summary);
                try { for (String id : match.sourceIds().stream().limit(3).toList()) { remaining.run(); sources.excerpt(project, id); } }
                catch (ResponseStatusException e) { match = new EndpointMatch(match.endpoint(), match.requestCount(), match.timeoutCount(), "STALE",
                    "入口文件已修改、不可读取或被排除，请重新索引。", List.of(), new VersionCheck("UNAVAILABLE", "当前文件尚未通过摘要校验。", match.endpoint().sourceHash(), null)); }
                entries.add(match);
            }
            var failures = snapshot.errors().stream().filter(value -> value.failureLocation() != null)
                .map(value -> new RequestFailure(value.timestamp(), value.traceId(), value.failureLocation())).toList();
            if (!failures.isEmpty()) {
                var evidence = new Evidence("CHECK-LOCATIONS", "query_error_logs", "接入检查", "本机检查", Map.of("failureLocations", failures));
                new SourceFailureLocations(project.index()).match(List.of(evidence), (id, focus) -> sources.excerptAt(project, id, focus), remaining).stream()
                    .flatMap(value -> value.frames().stream()).forEach(value -> positions.add(new Position(value.frame(), value.state(), value.message(), value.version())));
            }
            boolean stale = entries.stream().anyMatch(value -> "STALE".equals(value.state())) || positions.stream().anyMatch(value -> "STALE".equals(value.state()));
            boolean different = entries.stream().anyMatch(value -> "SOURCE_MISMATCH".equals(value.state())) || positions.stream().anyMatch(value -> "SOURCE_MISMATCH".equals(value.state()));
            boolean noEntries = entries.isEmpty();
            boolean located = !noEntries && entries.stream().allMatch(value -> "MATCHED".equals(value.state()))
                && positions.stream().allMatch(value -> "LINE_MATCH".equals(value.state()));
            steps.add(step("ENTRIES", "代码入口与位置", stale || different ? "BLOCKED" : located ? "PASS" : "WAIT",
                stale ? "当前源码与索引不一致或不可读取。" : different ? "观测位置的源码摘要与当前索引不同。"
                : noEntries ? "窗口没有可关联的 MVC 入口。" : located ? "本窗口的 " + entries.size() + " 个 MVC 入口已匹配，当前引用文件通过校验。" : "部分入口或错误位置未确定，详情列在下方。",
                stale ? "核对目录并重新索引。" : different ? "登记与运行构建对应的源码，再重新检查。" : located ? "" : "核对绑定目录、索引版本和接口观测设置。"));
            var versions = new ArrayList<VersionCheck>(); entries.forEach(value -> versions.add(value.version())); positions.forEach(value -> versions.add(value.version()));
            boolean checked = !versions.isEmpty() && versions.stream().allMatch(value -> value != null && "MATCHED".equals(value.state()));
            steps.add(step("BUILD", "构建源码摘要", different ? "BLOCKED" : checked && !stale ? "PASS" : "OPTIONAL",
                different ? "至少一个观测类的构建源码摘要与当前索引不同。" : checked && !stale ? "本窗口可核对位置的构建源码摘要与索引一致。" : "部分位置缺少可核验的构建摘要，尚未确认版本一致。",
                different ? "切换到对应构建的源码。" : checked && !stale ? "" : "需要版本核对时，生成构建清单并开启 source-version-checks。"));
            if (different) state = "SOURCE_VERSION_DIFFERENT"; else if (stale) state = "SOURCE_STALE";
            else if (located && checked && snapshot.requestCount() > 0) state = "READY";
        }
        var current = projects.byService(service).orElse(null);
        if (project == null ? current != null : current == null || !project.id().equals(current.id()) || project.revision() != current.revision()) {
            state = "PROJECT_CHANGED";
            steps.replaceAll(value -> Set.of("SOURCE", "ENTRIES", "BUILD").contains(value.id()) ? step(value.id(), value.title(), "WAIT", "检查期间源码项目已变化。", "刷新项目后重新检查。") : value);
            entries.clear(); positions.clear();
        }
        if (snapshot != null && snapshot.requestCount() == 0 && !Set.of("SOURCE_STALE", "SOURCE_VERSION_DIFFERENT", "PROJECT_CHANGED").contains(state)) state = "EMPTY";
        return new Check(view, Instant.now(), window, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started), state, snapshot != null,
            snapshot == null ? null : snapshot.requestCount(), project == null ? null : new Project(project.id(), project.name(), project.revision(), project.index().files().size(),
                project.index().files().stream().mapToInt(value -> value.symbols().size()).sum()), List.copyOf(steps), List.copyOf(entries), positions.stream().distinct().toList());
    }
    private Step step(String id, String title, String state, String message, String next) { return new Step(id, title, state, message, next); }
}
