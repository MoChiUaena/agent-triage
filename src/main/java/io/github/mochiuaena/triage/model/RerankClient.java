package io.github.mochiuaena.triage.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.mochiuaena.triage.execution.RunFailure;
import io.github.mochiuaena.triage.settings.SearchModelSettingsService.Credentials;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Optional rerank client for the Cohere/Jina-style POST {baseUrl}/rerank protocol:
 * request {model, query, documents}, response {results: [{index, relevance_score|score}]}.
 */
@Service
public class RerankClient {
    private static final HttpClient TRANSPORT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
        .followRedirects(HttpClient.Redirect.NEVER).build();
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int RESPONSE_LIMIT = 1024 * 1024;

    public record Scored(int index, double score) {}

    public List<Scored> rerank(Credentials credentials, String query, List<String> documents) {
        var factory = new JdkClientHttpRequestFactory(TRANSPORT);
        factory.setReadTimeout(credentials.timeout());
        RestClient rest = RestClient.builder().baseUrl(credentials.baseUrl()).requestFactory(factory)
            .requestInterceptor((request, body, execution) -> {
                ClientHttpResponse response = execution.execute(request, body);
                if (response.getStatusCode().isError()) return response;
                byte[] bytes = response.getBody().readNBytes(RESPONSE_LIMIT + 1);
                if (bytes.length > RESPONSE_LIMIT) {
                    response.close();
                    throw new RunFailure("MODEL_RESPONSE_LIMIT", "Rerank 响应超过大小限制。");
                }
                return new ClientHttpResponse() {
                    public HttpStatusCode getStatusCode() throws IOException { return response.getStatusCode(); }
                    public String getStatusText() throws IOException { return response.getStatusText(); }
                    public HttpHeaders getHeaders() { return response.getHeaders(); }
                    public InputStream getBody() { return new ByteArrayInputStream(bytes); }
                    public void close() { response.close(); }
                };
            })
            .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + credentials.apiKey())
            .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .build();
        String body;
        try {
            body = rest.post().uri("/rerank")
                .body(Map.of("model", credentials.model(), "query", query, "documents", documents))
                .retrieve().body(String.class);
        } catch (RestClientResponseException e) {
            throw new RunFailure("MODEL_HTTP_ERROR",
                "Rerank 服务返回 HTTP " + e.getStatusCode().value() + "，请检查配置或稍后重试。");
        } catch (RunFailure e) {
            throw e;
        } catch (Exception e) {
            throw new RunFailure("MODEL_HTTP_ERROR", "Rerank 服务连接失败或超时。");
        }
        return parse(body, documents.size());
    }

    private static List<Scored> parse(String body, int size) {
        JsonNode root;
        try { root = JSON.readTree(body); }
        catch (Exception e) { throw new RunFailure("MODEL_HTTP_ERROR", "Rerank 服务响应无法解析。"); }
        JsonNode results = root == null ? null : root.path("results");
        if (results == null || !results.isArray() || results.isEmpty())
            throw new RunFailure("MODEL_HTTP_ERROR", "Rerank 服务响应缺少 results。");
        List<Scored> scored = new ArrayList<>();
        for (JsonNode item : results) {
            int index = item.path("index").asInt(-1);
            JsonNode value = item.hasNonNull("relevance_score") ? item.path("relevance_score") : item.path("score");
            double score = value.asDouble(Double.NaN);
            if (index < 0 || index >= size || !Double.isFinite(score))
                throw new RunFailure("MODEL_HTTP_ERROR", "Rerank 服务返回的排序结果无效。");
            scored.add(new Scored(index, score));
        }
        return scored;
    }
}
