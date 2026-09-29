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
        STUB.requests.clear();
        Files.writeString(root.resolve("PrivateRoutingController.java"), """
            package fixture;
            class PrivateRoutingController {
                @GetMapping("/api/tickets/{id}") Object ticket(String id) { return null; }
                @GetMapping("/api/tickets/summary") Object ticket() { return null; }
            }
            """);
        sources.create("接口验证", "ticket-service", root.toString());
    }
    @AfterAll static void close() { STUB.close(); }
    private Run run(String endpoint, boolean source) {
        var headers = new HttpHeaders(); headers.setContentType(MediaType.APPLICATION_JSON); headers.set("X-Triage-Source", "1");
        var body = new HashMap<String,Object>(Map.of("question", "服务请求为什么变慢？", "service", "ticket-service", "windowMinutes", 5, "includeSource", source));
        if (endpoint != null) body.put("endpointId", endpoint);
        var created = http.postForEntity("/api/runs", new HttpEntity<>(body, headers), Run.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        UUID id = created.getBody().id();
        await().atMost(Duration.ofSeconds(8)).until(() -> http.getForObject("/api/runs/" + id, Run.class).status().terminal());
        Run result = http.getForObject("/api/runs/" + id, Run.class);
        assertThat(result.status()).isEqualTo(Status.SUCCEEDED); return result;
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
                        int count=all?5:bad?2:3, timeouts=all||bad?2:0;
                        var value=JSON.createObjectNode(); value.put("schemaVersion",3).put("kind","HTTP_ENDPOINTS").put("service","ticket-service").put("downstreamService","assignment-service")
                            .put("windowStart",end.minusSeconds(minutes*60L).toString()).put("windowEnd",end.toString()).put("requestCount",count).put("timeoutCount",timeouts).put("recordedRequestCount",5)
                            .put("requestP95Ms",timeouts>0?300:20).put("downstreamP95Ms",timeouts>0?280:10).put("downstreamTimeoutRate",(double)timeouts/count).putNull("baselineRequestP95Ms")
                            .put("synthetic",false).put("unattributedRequestCount",0).put("otherEndpointRequestCount",0);
                        value.set("endpoint",JSON.valueToTree(all?null:bad?failed:healthy)); var endpoints=value.putArray("endpoints");
                        if (all||bad) add(endpoints,failed,2,2); if (all||!bad) add(endpoints,healthy,3,0);
                        var errors=value.putArray("errors"); if (timeouts>0) errors.addObject().put("timestamp",end.minusSeconds(1).toString()).put("traceId","fixture-trace").put("level","ERROR").put("message","assignment-service request timeout");
                        respond(exchange,JSON.writeValueAsBytes(value));
                    } catch (Exception ignored) { }
                });
                server.createContext("/chat/completions", exchange -> {
                    try (exchange) {
                        JsonNode request=JSON.readTree(exchange.getRequestBody()); requests.add(request); Map<String,Object> message; String finish;
                        var evidence=new ArrayList<JsonNode>(); for (var input:request.path("messages")) if (input.path("role").asText().equals("tool")) JSON.readTree(input.path("content").asText()).forEach(evidence::add);
                        if (evidence.isEmpty()) {
                            var calls=new ArrayList<Map<String,Object>>(); for (String tool:List.of("read_service_metrics","query_error_logs","search_runbooks")) {
                                var args=new HashMap<String,Object>(Map.of("service","ticket-service","windowMinutes",5)); if (tool.equals("search_runbooks")) args.put("query","正常 超时");
                                calls.add(Map.of("id",tool,"type","function","function",Map.of("name",tool,"arguments",JSON.writeValueAsString(args)))); }
                            message=Map.of("role","assistant","tool_calls",calls); finish="tool_calls";
                        } else {
                            var ids=evidence.stream().filter(value -> value.path("source").asText().equals("read_service_metrics") || value.path("source").asText().equals("query_error_logs") || value.path("id").asText().startsWith("DOC-HEALTHY-BASELINE#")).map(value -> value.path("id").asText()).toList();
                            message=Map.of("role","assistant","content",JSON.writeValueAsString(Map.of("assessment","NO_DOWNSTREAM_TIMEOUT_OBSERVED","evidenceIds",ids,"nextChecks",List.of("FIND_SLOW_REQUEST")))); finish="stop";
                        }
                        respond(exchange,JSON.writeValueAsBytes(Map.of("id","endpoint-stub","created",1,"model","endpoint-stub","choices",List.of(Map.of("index",0,"finish_reason",finish,"message",message)),"usage",Map.of("prompt_tokens",10,"completion_tokens",5,"total_tokens",15))));
                    } catch (Exception ignored) { }
                }); server.start();
            } catch (Exception e) { throw new IllegalStateException(e); }
        }
        static void add(ArrayNode values, RequestEndpoint endpoint, int count, int timeouts) {
            var item=values.addObject(); item.set("endpoint",JSON.valueToTree(endpoint)); item.put("requestCount",count).put("timeoutCount",timeouts).put("requestP95Ms",timeouts>0?300:20).put("downstreamP95Ms",timeouts>0?280:10);
        }
        static void respond(com.sun.net.httpserver.HttpExchange exchange, byte[] value) throws java.io.IOException { exchange.getResponseHeaders().set("Content-Type","application/json"); exchange.sendResponseHeaders(200,value.length); exchange.getResponseBody().write(value); }
        String url() { return "http://127.0.0.1:"+server.getAddress().getPort(); }
        public void close() { server.stop(0); workers.shutdownNow(); }
    }
}
