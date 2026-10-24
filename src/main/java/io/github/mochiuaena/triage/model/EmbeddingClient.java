package io.github.mochiuaena.triage.model;

import io.github.mochiuaena.triage.execution.RunFailure;
import io.github.mochiuaena.triage.settings.SearchModelSettingsService.Credentials;

import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResponseErrorHandler;
import org.springframework.web.client.RestClient;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Blocking OpenAI-compatible embeddings client for the optional vector index. One session per job or
 * search request; the credential is held only for that call and never logged or persisted.
 */
@Service
public class EmbeddingClient {
    private static final HttpClient TRANSPORT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
        .followRedirects(HttpClient.Redirect.NEVER).build();
    private static final int RESPONSE_LIMIT = 8 * 1024 * 1024;

    public record Batch(List<float[]> vectors, int dimensions, Integer promptTokens, Integer totalTokens) {}

    public Session session(Credentials credentials) { return new Session(build(credentials), credentials.model()); }

    public static final class Session {
        private final OpenAiApi api;
        private final String model;

        private Session(OpenAiApi api, String model) {
            this.api = api;
            this.model = model;
        }

        public Batch embed(List<String> texts) {
            if (texts == null || texts.isEmpty()) throw new RunFailure("MODEL_HTTP_ERROR", "Embedding 请求不能为空。");
            var body = api.embeddings(new OpenAiApi.EmbeddingRequest<>(List.copyOf(texts), model)).getBody();
            if (body == null || body.data() == null || body.data().size() != texts.size())
                throw new RunFailure("MODEL_HTTP_ERROR", "Embedding 服务返回的向量数量与请求不一致。");
            List<float[]> vectors = new ArrayList<>();
            int dimensions = -1;
            for (var item : body.data()) {
                float[] vector = item.embedding();
                if (vector == null || vector.length == 0)
                    throw new RunFailure("MODEL_HTTP_ERROR", "Embedding 服务返回了空向量。");
                if (dimensions == -1) dimensions = vector.length;
                else if (vector.length != dimensions)
                    throw new RunFailure("MODEL_HTTP_ERROR", "Embedding 服务返回的向量维度不一致。");
                vectors.add(vector);
            }
            var usage = body.usage();
            return new Batch(List.copyOf(vectors), dimensions, usage == null ? null : usage.promptTokens(),
                usage == null ? null : usage.totalTokens());
        }
    }

    private static OpenAiApi build(Credentials credentials) {
        var factory = new JdkClientHttpRequestFactory(TRANSPORT);
        factory.setReadTimeout(credentials.timeout());
        var rest = RestClient.builder().requestFactory(factory).requestInterceptor((request, body, execution) -> {
            ClientHttpResponse response = execution.execute(request, body);
            if (response.getStatusCode().isError()) return response;
            byte[] bytes = response.getBody().readNBytes(RESPONSE_LIMIT + 1);
            if (bytes.length > RESPONSE_LIMIT) {
                response.close();
                throw new RunFailure("MODEL_RESPONSE_LIMIT", "Embedding 响应超过大小限制，请减小批次或维度。");
            }
            return new ClientHttpResponse() {
                public org.springframework.http.HttpStatusCode getStatusCode() throws IOException { return response.getStatusCode(); }
                public String getStatusText() throws IOException { return response.getStatusText(); }
                public org.springframework.http.HttpHeaders getHeaders() { return response.getHeaders(); }
                public InputStream getBody() { return new ByteArrayInputStream(bytes); }
                public void close() { response.close(); }
            };
        });
        return OpenAiApi.builder().baseUrl(credentials.baseUrl()).apiKey(credentials.apiKey())
            .embeddingsPath("/embeddings").restClientBuilder(rest)
            .responseErrorHandler(new ResponseErrorHandler() {
                public boolean hasError(ClientHttpResponse response) throws IOException { return response.getStatusCode().isError(); }
                public void handleError(URI uri, HttpMethod method, ClientHttpResponse response) throws IOException {
                    throw new RunFailure("MODEL_HTTP_ERROR",
                        "Embedding 服务返回 HTTP " + response.getStatusCode().value() + "，请检查配置或稍后重试。");
                }
            }).build();
    }
}
