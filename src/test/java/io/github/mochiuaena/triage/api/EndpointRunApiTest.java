package io.github.mochiuaena.triage.api;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.sun.net.httpserver.HttpServer;
import io.github.mochiuaena.triage.domain.TriageModel.*;
import io.github.mochiuaena.triage.settings.*;
import io.github.mochiuaena.triage.source.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={"triage.mode=DEMO", "triage.observation.source=LIVE",
    "spring.datasource.url=jdbc:h2:mem:endpoint-api;DB_CLOSE_DELAY=-1", "triage.settings.key-file=target/endpoint-api-key"})
class EndpointRunApiTest {
    static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    static final Stub STUB = new Stub();
    @Autowired TestRestTemplate http;
    @Autowired SourceProjectService sources;
    @Autowired ProviderRegistry providers;
    @Autowired JdbcTemplate jdbc;
    @TempDir Path root;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry values) {
        values.add("triage.services[0].base-url", STUB::url);
        values.add("triage.services[0].id", () -> "ticket-service"); values.add("triage.services[0].name", () -> "工单服务");
        values.add("triage.services[0].downstream-id", () -> "assignment-service"); values.add("triage.services[0].downstream-name", () -> "分配服务");
        values.add("triage.services[0].protocol", () -> "OBSERVATIONS_V3"); values.add("triage.services[0].max-window-minutes", () -> "15");
    }
    @BeforeEach void setup() throws Exception {
        jdbc.update("DELETE FROM source_projects"); jdbc.update("DELETE FROM model_selection"); jdbc.update("DELETE FROM model_providers");
        STUB.requests.clear(); STUB.locations = false; STUB.requestError = false; STUB.sourceHash = null; STUB.handlerHash = null;
        Files.writeString(root.resolve("PrivateRoutingController.java"), """
            package fixture;
            class PrivateRoutingController {
                @GetMapping("/api/tickets/{id}") Object ticket(String id) { return null; }
                @GetMapping("/api/tickets/summary") Object ticket() { return null; }
            }
            """);
        Files.writeString(root.resolve("StackOnlyProbe.java"), """
            package fixture;
            class StackOnlyProbe {
                Object locate() {
                    // local-frame-private-marker
                    return null;
                }
            }
            """);
        sources.create("接口验证", "ticket-service", root.toString());
    }
    @AfterAll static void close() { STUB.close(); }
    private Run run(String endpoint, boolean source) {
        return run(endpoint, source, false);
    }
    private Run run(String endpoint, boolean source, boolean allowSourceModel) {
        return run(endpoint, source, allowSourceModel, Status.SUCCEEDED);
    }
    private Run run(String endpoint, boolean source, boolean allowSourceModel, Status expectedStatus) {
        var headers = new HttpHeaders(); headers.setContentType(MediaType.APPLICATION_JSON); headers.set("X-Triage-Source", "1");
        var body = new HashMap<String,Object>(Map.of("question", "服务请求为什么变慢？", "service", "ticket-service", "windowMinutes", 5, "includeSource", source));
        if (allowSourceModel) {
            body.put("allowSourceModel", true); body.put("expectedSelection", providers.current().selectionToken());
            var project = sources.bound("ticket-service"); body.put("expectedSourceRevision", project.revision()); body.put("expectedSourceProjectId", project.id());
        }
        if (endpoint != null) body.put("endpointId", endpoint);
        var created = http.postForEntity("/api/runs", new HttpEntity<>(body, headers), Run.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        UUID id = created.getBody().id();
        await().atMost(Duration.ofSeconds(8)).until(() -> http.getForObject("/api/runs/" + id, Run.class).status().terminal());
        Run result = http.getForObject("/api/runs/" + id, Run.class);
        assertThat(result.status()).as("saved failure: %s", result.failure()).isEqualTo(expectedStatus); return result;
    }
    @Test void ordinaryRequestExceptionsRemainUnattributedAndInsufficient() {
        STUB.requestError = true; STUB.locations = true;
        Run result = run(STUB.failed.id(), true, false, Status.INSUFFICIENT_EVIDENCE);
        var logs = result.evidence().stream().filter(value -> value.source().equals("query_error_logs")).findFirst().orElseThrow();
        assertThat(logs.summary()).contains("请求错误").doesNotContain("下游请求错误");
        assertThat(result.diagnosis().possibleCauses()).isEmpty();
        assertThat(result.diagnosis().nextSteps()).allSatisfy(value -> assertThat(value).doesNotContain("下游请求错误"));
        assertThat(result.diagnosis().uncertainty()).contains("不能确认下游归因");
        assertThat(result.sourceAnalysis().graph().failureMatches().getFirst().kind()).isEqualTo("REQUEST_EXCEPTION");
    }
    @Test void separatesHealthyAndTimeoutEndpointsAndUsesObservedHandlerToFindAnEntry() {
        Run healthy = run(STUB.healthy.id(), true);
        assertThat(healthy.endpoint()).isEqualTo(STUB.healthy);
        var metrics = healthy.evidence().stream().filter(value -> value.source().equals("read_service_metrics")).findFirst().orElseThrow();
        assertThat(metrics.data()).containsEntry("requestCount", 3).containsEntry("timeoutCount", 0);
        var graph = healthy.sourceAnalysis().graph();
        assertThat(graph.endpointMatches()).hasSize(1);
        assertThat(graph.endpointMatches().getFirst().state()).isEqualTo("MATCHED");
        assertThat(graph.nodes().getFirst().signature()).isEqualTo("ticket()");
        Run failed = run(STUB.failed.id(), true);
        assertThat(failed.sourceAnalysis().graph().nodes().getFirst().signature()).isEqualTo("ticket(String)");
        var fault = failed.evidence().stream().filter(value -> value.source().equals("read_service_metrics")).findFirst().orElseThrow();
        assertThat(fault.data()).containsEntry("requestCount", 2).containsEntry("timeoutCount", 2);
        assertThat(http.getForObject("/api/runs/" + healthy.id(), Run.class)).isEqualTo(healthy);
        var headers = new HttpHeaders(); headers.set("Origin", "https://foreign.example");
        assertThat(http.exchange("/api/services/ticket-service/endpoints", HttpMethod.GET, new HttpEntity<>(headers), String.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(http.postForEntity("/api/runs", Map.of("question", "服务请求为什么慢？", "service", "ticket-service", "windowMinutes", 5, "endpointId", "EP-" + "f".repeat(32)), String.class).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }
    @Test void routingIdentifiersStayLocalWhenRuntimeEvidenceIsSentToAModel() {
        var provider = providers.create(new ProviderConfig.Input("接口协议验证", ProviderConfig.Protocol.OPENAI_COMPATIBLE, STUB.url(), "endpoint-stub", "test-only-local", .2, 2, 4, 1600, 0));
        providers.select("MODEL", provider.id());
        Run result = run(STUB.healthy.id(), false);
        assertThat(result.evidence().stream().filter(value -> value.source().equals("read_service_metrics")).findFirst().orElseThrow().data()).containsKey("requestDetails");
        assertThat(STUB.requests).hasSize(2);
        assertThat(STUB.requests.toString()).doesNotContain("PrivateRoutingController", "/api/tickets/summary", "MVC_SELECTED", "requestDetails", STUB.healthy.id());
        assertThat(result.modelExecution().assessment()).isEqualTo("NO_DOWNSTREAM_TIMEOUT_OBSERVED");
    }
    @Test void failureLocationsAndFocusedCodeRemainLocalEvenWithCandidateSourceConsent() {
        STUB.locations = true;
        var provider = providers.create(new ProviderConfig.Input("错误位置协议验证", ProviderConfig.Protocol.OPENAI_COMPATIBLE, STUB.url(), "endpoint-stub", "test-only-local", .2, 2, 4, 1600, 0));
        providers.select("MODEL", provider.id());
        var project = sources.bound("ticket-service");
        sources.sharing(project.id(), project.revision(), true, provider.id(), provider.version(), providers.current().selectionToken());
        Run result = run(STUB.failed.id(), true, true);
        var location = result.sourceAnalysis().graph().failureMatches().getFirst();
        assertThat(location.traceId()).isEqualTo("fixture-trace");
        assertThat(location.frames().getFirst().state()).isEqualTo("LINE_MATCH");
        assertThat(location.frames().getFirst().excerpts().getFirst().content()).contains("local-frame-private-marker");
        assertThat(location.frames().getFirst().excerpts().getFirst().startLine()).isLessThanOrEqualTo(5);
        assertThat(result.sourceAnalysis().state()).isEqualTo("MODEL_SELECTED");
        assertThat(STUB.requests).hasSize(3);
        assertThat(STUB.requests.toString()).doesNotContain("StackOnlyProbe", "LocalFailureMarker", "failureLocations", "local-frame-private-marker");
        assertThat(STUB.requests.getLast().toString()).contains("PrivateRoutingController");
        sources.reindex(project.id());
        assertThat(http.getForObject("/api/runs/" + result.id(), Run.class).sourceAnalysis().graph().failureMatches()).isEqualTo(result.sourceAnalysis().graph().failureMatches());
    }
    @Test void differentBuildSourceDigestKeepsRuntimeDiagnosisAndPreventsSourceModelDispatch() {
        STUB.locations = true; STUB.sourceHash = "e".repeat(64);
        var provider = providers.create(new ProviderConfig.Input("版本协议验证", ProviderConfig.Protocol.OPENAI_COMPATIBLE, STUB.url(), "endpoint-stub", "test-only-local", .2, 2, 4, 1600, 0));
        providers.select("MODEL", provider.id()); var project = sources.bound("ticket-service");
        sources.sharing(project.id(), project.revision(), true, provider.id(), provider.version(), providers.current().selectionToken());
        Run result = run(STUB.failed.id(), true, true);
        assertThat(result.sourceAnalysis().state()).isEqualTo("SOURCE_VERSION_DIFFERENT");
        assertThat(result.sourceAnalysis().excerpts()).isEmpty(); assertThat(result.sourceAnalysis().graph().nodes()).isEmpty();
        var frame = result.sourceAnalysis().graph().failureMatches().getFirst().frames().getFirst();
        assertThat(frame.state()).isEqualTo("SOURCE_MISMATCH"); assertThat(frame.version().state()).isEqualTo("DIFFERENT"); assertThat(frame.excerpts()).isEmpty();
        assertThat(result.diagnosis()).isNotNull(); assertThat(STUB.requests).hasSize(2);
        assertThat(STUB.requests.toString()).doesNotContain("sourceHash", "e".repeat(64), "local-frame-private-marker");
        assertThat(http.getForObject("/api/runs/" + result.id(), Run.class).sourceAnalysis()).isEqualTo(result.sourceAnalysis());
    }
    @Test void handlerBuildDigestStopsAStaleEntryEvenWhenNoErrorFramesArePresent() {
        STUB.handlerHash = "d".repeat(64);
        Run result = run(STUB.healthy.id(), true);
        assertThat(result.sourceAnalysis().state()).isEqualTo("SOURCE_VERSION_DIFFERENT");
        assertThat(result.sourceAnalysis().graph().endpointMatches().getFirst().state()).isEqualTo("SOURCE_MISMATCH");
        assertThat(result.sourceAnalysis().graph().endpointMatches().getFirst().version().state()).isEqualTo("DIFFERENT");
        assertThat(result.sourceAnalysis().excerpts()).isEmpty();
    }
    private SourceReadinessService.Check readiness() {
        return http.getForObject("/api/services/ticket-service/source-check?windowMinutes=5", SourceReadinessService.Check.class);
    }
    @Test void sourcePreflightReadsObservationsAndIndexWithoutCreatingRunsCallingModelsOrReturningCode() {
        var provider = providers.create(new ProviderConfig.Input("接入检查协议验证", ProviderConfig.Protocol.OPENAI_COMPATIBLE, STUB.url(), "endpoint-stub", "test-only-local", .2, 2, 4, 1600, 0));
        providers.select("MODEL", provider.id());
        Long before = jdbc.queryForObject("SELECT COUNT(*) FROM triage_runs", Long.class);
        var check = readiness();
        assertThat(check.state()).isEqualTo("PARTIAL"); assertThat(check.observationsAvailable()).isTrue(); assertThat(check.requestCount()).isEqualTo(5);
        assertThat(check.project().id()).isEqualTo(sources.bound("ticket-service").id());
        assertThat(check.steps()).extracting(SourceReadinessService.Step::state).containsExactly("PASS", "PASS", "PASS", "PASS", "OPTIONAL");
        assertThat(check.endpoints()).hasSize(2); assertThat(STUB.requests).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM triage_runs", Long.class)).isEqualTo(before);
        String body = http.getForObject("/api/services/ticket-service/source-check", String.class);
        assertThat(body).doesNotContain(root.toString(), STUB.url(), "local-frame-private-marker", "return null", "apiKey");
    }
    @Test void preflightSeparatesVerifiedBuildMissingSourceAndModifiedFiles() throws Exception {
        var project = sources.bound("ticket-service");
        STUB.handlerHash = project.index().files().stream().filter(value -> value.path().equals("PrivateRoutingController.java")).findFirst().orElseThrow().hash();
        assertThat(readiness().state()).isEqualTo("READY");
        Files.writeString(root.resolve("PrivateRoutingController.java"), Files.readString(root.resolve("PrivateRoutingController.java")) + "\n// local edit\n");
        var stale = readiness(); assertThat(stale.state()).isEqualTo("SOURCE_STALE");
        assertThat(stale.endpoints()).allMatch(value -> value.state().equals("STALE"));
        assertThat(stale.steps().get(3).nextAction()).contains("重新索引");
        assertThat(stale.steps().get(4).state()).isEqualTo("SKIPPED"); assertThat(stale.steps().get(4).nextAction()).contains("重新索引");
        sources.delete(project.id(), project.revision());
        var missing = readiness(); assertThat(missing.project()).isNull(); assertThat(missing.observationsAvailable()).isTrue();
        assertThat(missing.steps().get(2).state()).isEqualTo("WAIT"); assertThat(missing.steps().get(3).state()).isEqualTo("SKIPPED");
    }
    @Test void preflightChecksObservedErrorPositionsWithoutReturningTheirSourceExcerpts() {
        var project = sources.bound("ticket-service"); STUB.locations = true;
        STUB.handlerHash = project.index().files().stream().filter(value -> value.path().equals("PrivateRoutingController.java")).findFirst().orElseThrow().hash();
        STUB.sourceHash = "e".repeat(64);
        var different = readiness(); assertThat(different.state()).isEqualTo("SOURCE_VERSION_DIFFERENT");
        assertThat(different.errorPositions()).hasSize(1); assertThat(different.errorPositions().getFirst().state()).isEqualTo("SOURCE_MISMATCH");
        assertThat(different.steps().get(4).state()).isEqualTo("BLOCKED"); assertThat(STUB.requests).isEmpty();
    }
    @Test void preflightRejectsUnregisteredServicesInvalidWindowsAndForeignOrigin() {
        for (String path : List.of("/api/services/missing-service/source-check", "/api/services/ticket-service/source-check?windowMinutes=0", "/api/services/ticket-service/source-check?windowMinutes=16")) {
            var response = http.getForEntity(path, String.class); assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody()).doesNotContain(STUB.url(), root.toString());
        }
        var headers = new HttpHeaders(); headers.setOrigin("https://invalid.example");
        assertThat(http.exchange("/api/services/ticket-service/source-check", HttpMethod.GET, new HttpEntity<>(headers), String.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
    static RequestEndpoint endpoint(String route, List<String> parameters) {
        try {
            String identity = String.join("\0", "GET", route, "fixture.PrivateRoutingController", "ticket", String.join(",", parameters));
            String id = "EP-" + HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(identity.getBytes(StandardCharsets.UTF_8))).substring(0,32);
            return new RequestEndpoint(id, "GET", route, "fixture.PrivateRoutingController", "ticket", parameters, "MVC_SELECTED");
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
    static class Stub implements AutoCloseable {
        final HttpServer server;
        final ExecutorService workers = Executors.newCachedThreadPool();
        final List<JsonNode> requests = new CopyOnWriteArrayList<>();
        volatile boolean locations;
        volatile boolean requestError;
        volatile String sourceHash;
        volatile String handlerHash;
        final RequestEndpoint healthy = endpoint("/api/tickets/summary", List.of());
        final RequestEndpoint failed = endpoint("/api/tickets/{id}", List.of("java.lang.String"));
        Stub() {
            try {
                server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0); server.setExecutor(workers);
                server.createContext("/triage/endpoint-observations", exchange -> {
                    try (exchange) {
                        var query = new HashMap<String,String>(); for (String pair : exchange.getRequestURI().getRawQuery().split("&")) { String[] part=pair.split("=",2); query.put(part[0], URLDecoder.decode(part[1], StandardCharsets.UTF_8)); }
                        String selected=query.get("endpointId"); boolean bad=failed.id().equals(selected); boolean all=selected==null;
                        if (selected!=null && !selected.equals(healthy.id()) && !bad) { exchange.sendResponseHeaders(409,-1); return; }
                        Instant end=Instant.parse(query.get("endTime")); int minutes=Integer.parseInt(query.get("windowMinutes"));
                        int count=all?5:bad?2:3, timeouts=requestError?0:all||bad?2:0;
                        var value=JSON.createObjectNode(); value.put("schemaVersion",3).put("kind","HTTP_ENDPOINTS").put("service","ticket-service").put("downstreamService","assignment-service")
                            .put("windowStart",end.minusSeconds(minutes*60L).toString()).put("windowEnd",end.toString()).put("requestCount",count).put("timeoutCount",timeouts).put("recordedRequestCount",5)
                            .put("requestP95Ms",timeouts>0?300:20).put("downstreamP95Ms",timeouts>0?280:10).put("downstreamTimeoutRate",(double)timeouts/count).putNull("baselineRequestP95Ms")
                            .put("synthetic",false).put("unattributedRequestCount",0).put("otherEndpointRequestCount",0);
                        RequestEndpoint currentHealthy = hashed(healthy), currentFailed = hashed(failed);
                        value.set("endpoint",JSON.valueToTree(all?null:bad?currentFailed:currentHealthy)); var endpoints=value.putArray("endpoints");
                        if (all||bad) add(endpoints,currentFailed,2,requestError?0:2); if (all||!bad) add(endpoints,currentHealthy,3,0);
                        var errors=value.putArray("errors");
                        if (timeouts>0 || requestError && (bad || all)) {
                            var error = errors.addObject().put("timestamp",end.minusSeconds(1).toString()).put("traceId","fixture-trace").put("level","ERROR").put("message",requestError ? "HTTP request failed" : "assignment-service request timeout");
                            if (locations) {
                                var detail = error.putObject("failureLocation").put("kind",requestError ? "REQUEST_EXCEPTION" : "HTTP_CLIENT_FAILURE").put("truncated",false);
                                detail.putArray("exceptionTypes").add("fixture.LocalFailureMarker");
                                var frame = detail.putArray("frames").addObject().put("className","fixture.StackOnlyProbe").put("methodName","locate").put("fileName","StackOnlyProbe.java").put("lineNumber",5);
                                if (sourceHash != null) frame.put("sourceHash", sourceHash);
                            }
                        }
                        respond(exchange,JSON.writeValueAsBytes(value));
                    } catch (Exception ignored) { }
                });
                server.createContext("/chat/completions", exchange -> {
                    try (exchange) {
                        JsonNode request=JSON.readTree(exchange.getRequestBody()); requests.add(request); Map<String,Object> message; String finish;
                        var evidence=new ArrayList<JsonNode>(); for (var input:request.path("messages")) if (input.path("role").asText().equals("tool")) JSON.readTree(input.path("content").asText()).forEach(evidence::add);
                        if (request.path("messages").get(0).path("content").asText().contains("SOURCE_SELECTION")) {
                            var payload = JSON.readTree(request.path("messages").get(request.path("messages").size()-1).path("content").asText());
                            message=Map.of("role","assistant","content",JSON.writeValueAsString(Map.of("sourceIds",List.of(payload.path("candidates").get(0).path("id").asText())))); finish="stop";
                        } else if (evidence.isEmpty()) {
                            var calls=new ArrayList<Map<String,Object>>(); for (String tool:List.of("read_service_metrics","query_error_logs","search_runbooks")) {
                                var args=new HashMap<String,Object>(Map.of("service","ticket-service","windowMinutes",5)); if (tool.equals("search_runbooks")) args.put("query","正常 超时");
                                calls.add(Map.of("id",tool,"type","function","function",Map.of("name",tool,"arguments",JSON.writeValueAsString(args)))); }
                            message=Map.of("role","assistant","tool_calls",calls); finish="tool_calls";
                        } else {
                            boolean timeout = evidence.stream().anyMatch(value -> value.path("source").asText().equals("read_service_metrics") && value.path("data").path("timeoutCount").asInt() > 0);
                            var ids=evidence.stream().filter(value -> value.path("source").asText().equals("read_service_metrics") || value.path("source").asText().equals("query_error_logs") || value.path("id").asText().startsWith(timeout ? "DOC-DOWNSTREAM-TIMEOUT#" : "DOC-HEALTHY-BASELINE#")).map(value -> value.path("id").asText()).toList();
                            message=Map.of("role","assistant","content",JSON.writeValueAsString(Map.of("assessment",timeout ? "DOWNSTREAM_TIMEOUT_OBSERVED" : "NO_DOWNSTREAM_TIMEOUT_OBSERVED","evidenceIds",ids,"nextChecks",List.of("FIND_SLOW_REQUEST")))); finish="stop";
                        }
                        respond(exchange,JSON.writeValueAsBytes(Map.of("id","endpoint-stub","created",1,"model","endpoint-stub","choices",List.of(Map.of("index",0,"finish_reason",finish,"message",message)),"usage",Map.of("prompt_tokens",10,"completion_tokens",5,"total_tokens",15))));
                    } catch (Exception ignored) { }
                }); server.start();
            } catch (Exception e) { throw new IllegalStateException(e); }
        }
        RequestEndpoint hashed(RequestEndpoint endpoint) { return new RequestEndpoint(endpoint.id(), endpoint.httpMethod(), endpoint.routeTemplate(), endpoint.handlerClass(), endpoint.handlerMethod(), endpoint.parameterTypes(), endpoint.stage(), handlerHash); }
        static void add(ArrayNode values, RequestEndpoint endpoint, int count, int timeouts) {
            var item=values.addObject(); item.set("endpoint",JSON.valueToTree(endpoint)); item.put("requestCount",count).put("timeoutCount",timeouts).put("requestP95Ms",timeouts>0?300:20).put("downstreamP95Ms",timeouts>0?280:10);
        }
        static void respond(com.sun.net.httpserver.HttpExchange exchange, byte[] value) throws java.io.IOException { exchange.getResponseHeaders().set("Content-Type","application/json"); exchange.sendResponseHeaders(200,value.length); exchange.getResponseBody().write(value); }
        String url() { return "http://127.0.0.1:"+server.getAddress().getPort(); }
        public void close() { server.stop(0); workers.shutdownNow(); }
    }
}
