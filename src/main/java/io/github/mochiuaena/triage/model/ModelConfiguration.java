package io.github.mochiuaena.triage.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.mochiuaena.triage.execution.RunFailure;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.http.*;
import org.springframework.http.client.*;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.web.client.ResponseErrorHandler;
import org.springframework.web.client.RestClient;
import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;

public final class ModelConfiguration {
    private static final HttpClient TRANSPORT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
        .followRedirects(HttpClient.Redirect.NEVER).build();
    private ModelConfiguration() {}

    public static ChatClient createClient(ModelSettings settings) {
        return createClient(settings, true, 0.0);
    }

    public static ChatClient createClient(ModelSettings settings, boolean deepSeek, double temperature) {
        settings.requireCredentials();
        var factory = new JdkClientHttpRequestFactory(TRANSPORT);
        factory.setReadTimeout(settings.timeout());
        var rest = RestClient.builder().requestFactory(factory).requestInterceptor((request, body, execution) -> {
            ClientHttpResponse response = execution.execute(request, body);
            if (response.getStatusCode().isError()) return response;
            try {
                byte[] bytes = response.getBody().readNBytes(262_145);
                if (bytes.length > 262_144) throw new RunFailure("MODEL_RESPONSE_LIMIT", "模型响应超过大小限制。");
                return new ClientHttpResponse() {
                    public HttpStatusCode getStatusCode() throws IOException { return response.getStatusCode(); }
                    public String getStatusText() throws IOException { return response.getStatusText(); }
                    public HttpHeaders getHeaders() { return response.getHeaders(); }
                    public InputStream getBody() { return new ByteArrayInputStream(bytes); }
                    public void close() { response.close(); }
                };
            } catch (IOException | RuntimeException e) {
                response.close();
                throw e;
            }
        });
        var api = OpenAiApi.builder().baseUrl(settings.baseUrl()).apiKey(settings.apiKey())
            .completionsPath("/chat/completions").restClientBuilder(rest)
            .responseErrorHandler(new ResponseErrorHandler() {
                public boolean hasError(ClientHttpResponse response) throws IOException { return response.getStatusCode().isError(); }
                public void handleError(URI uri, HttpMethod method, ClientHttpResponse response) throws IOException {
                    throw new RunFailure("MODEL_HTTP_ERROR", "模型服务返回 HTTP " + response.getStatusCode().value() + "，请检查配置或稍后重试。");
                }
            }).build();
        var options = OpenAiChatOptions.builder().model(settings.name()).temperature(temperature)
            .maxTokens(settings.maxTokens()).internalToolExecutionEnabled(false);
        if (deepSeek) options.extraBody(java.util.Map.of("thinking", java.util.Map.of("type", "disabled")));
        var model = OpenAiChatModel.builder().openAiApi(api).defaultOptions(options.build())
            .retryTemplate(RetryTemplate.builder().maxAttempts(1).build()).build();
        return ChatClient.builder(model).build();
    }
}
