package io.github.mochiuaena.triage.sdk;

import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.web.client.RestClientAutoConfiguration;
import org.springframework.boot.autoconfigure.web.client.RestTemplateAutoConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.*;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.mock.web.*;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;

class HttpClientResponseObservationTest {
    private final WebApplicationContextRunner runner = new WebApplicationContextRunner().withConfiguration(
        AutoConfigurations.of(RestClientAutoConfiguration.class, RestTemplateAutoConfiguration.class, TriageObservationAutoConfiguration.class))
        .withPropertyValues("triage.sdk.enabled=true", "triage.sdk.service-id=catalog-service", "triage.sdk.downstream-id=inventory-service",
            "triage.sdk.downstream-base-url=http://127.0.0.1:18084");

    @ParameterizedTest @CsvSource({"template,acquire", "client,acquire", "template,read", "client,read", "template,runtime", "client,runtime", "template,plain", "client,plain"})
    void responseFailureIsCountedWithoutChangingTheException(String type, String stage) {
        runner.run(context -> {
            var recorder = context.getBean(ObservationRecorder.class);
            Throwable failure = stage.equals("plain") ? new IOException("Read timed out: private-body-failure") : stage.equals("runtime")
                ? new IllegalStateException("private-body-failure", new SocketTimeoutException("private-cause"))
                : new SocketTimeoutException("private-body-failure");
            var closed = new AtomicInteger();
            var delegate = new ClientHttpResponse() {
                @Override public HttpStatusCode getStatusCode() { return HttpStatus.OK; }
                @Override public String getStatusText() { return "OK"; }
                @Override public HttpHeaders getHeaders() { return new HttpHeaders(); }
                @Override public void close() { closed.incrementAndGet(); }
                @Override public InputStream getBody() throws IOException {
                    if (stage.equals("acquire")) throw (IOException) failure;
                    return new InputStream() {
                        @Override public int read() throws IOException {
                            if (failure instanceof IOException io) throw io;
                            throw (RuntimeException) failure;
                        }
                    };
                }
            };
            RestTemplate template = type.equals("template") ? context.getBean(RestTemplateBuilder.class).build() : null;
            RestClient.Builder builder = type.equals("client") ? context.getBean(RestClient.Builder.class) : null;
            var transport = template != null ? MockRestServiceServer.bindTo(template).build() : MockRestServiceServer.bindTo(builder).build();
            RestClient client = builder != null ? builder.build() : null;
            String url = "http://127.0.0.1:18084/private-response";
            transport.expect(requestTo(url)).andRespond(request -> delegate);
            new TriageRequestFilter(recorder).doFilter(new MockHttpServletRequest("GET", "/api/products/demo"),
                new MockHttpServletResponse(), (request, response) -> {
                    var thrown = catchThrowable(() -> {
                        if (template != null) template.execute(url, HttpMethod.GET, null, body -> { body.getBody().read(new byte[2]); return null; });
                        else client.get().uri(url).exchange((httpRequest, body) -> { body.getBody().read(new byte[2]); return null; });
                    });
                    if (failure instanceof IOException) assertThat(thrown).hasCause(failure);
                    else assertThat(thrown).isSameAs(failure);
                });
            var window = (ObservationRecorder.HttpWindow) recorder.snapshot(5, Instant.now());
            assertThat(window.timeoutCount()).isEqualTo(stage.equals("plain") ? 0 : 1);
            assertThat(window.requestCount()).isEqualTo(1);
            assertThat(window.toString()).doesNotContain("private-body-failure", "private-cause", "private-response");
            assertThat(closed.get()).isEqualTo(1);
            transport.verify();
        });
    }
    @ParameterizedTest @CsvSource({"template,status", "client,status", "template,text", "client,text", "template,headers", "client,headers"})
    void lazyMetadataFailuresAreCountedAndKeepApplicationExceptionIdentity(String type, String stage) {
        runner.run(context -> {
            var recorder = context.getBean(ObservationRecorder.class);
            Throwable failure = stage.equals("headers")
                ? new IllegalStateException("private-metadata-failure", new SocketTimeoutException("private-cause"))
                : new SocketTimeoutException("private-metadata-failure");
            var closed = new AtomicInteger();
            var delegate = new ClientHttpResponse() {
                @Override public HttpStatusCode getStatusCode() throws IOException {
                    if (stage.equals("status")) throw (IOException) failure;
                    return HttpStatus.OK;
                }
                @Override public String getStatusText() throws IOException {
                    if (stage.equals("text")) throw (IOException) failure;
                    return "OK";
                }
                @Override public HttpHeaders getHeaders() {
                    if (stage.equals("headers")) throw (RuntimeException) failure;
                    return new HttpHeaders();
                }
                @Override public void close() { closed.incrementAndGet(); }
                @Override public InputStream getBody() { return InputStream.nullInputStream(); }
            };
            RestTemplate template = type.equals("template") ? context.getBean(RestTemplateBuilder.class).build() : null;
            RestClient.Builder builder = type.equals("client") ? context.getBean(RestClient.Builder.class) : null;
            var transport = template != null ? MockRestServiceServer.bindTo(template).build() : MockRestServiceServer.bindTo(builder).build();
            RestClient client = builder != null ? builder.build() : null;
            String url = "http://127.0.0.1:18084/private-response";
            transport.expect(requestTo(url)).andRespond(request -> delegate);
            new TriageRequestFilter(recorder).doFilter(new MockHttpServletRequest("GET", "/api/products/demo"),
                new MockHttpServletResponse(), (request, response) -> {
                    var thrown = catchThrowable(() -> {
                        if (template != null) template.execute(url, HttpMethod.GET, null, metadata -> readMetadata(metadata, stage));
                        else client.get().uri(url).exchange((httpRequest, metadata) -> readMetadata(metadata, stage));
                    });
                    if (failure instanceof IOException) assertThat(thrown).hasCause(failure);
                    else assertThat(thrown).isSameAs(failure);
                });
            var window = (ObservationRecorder.HttpWindow) recorder.snapshot(5, Instant.now());
            assertThat(window.timeoutCount()).isEqualTo(1);
            assertThat(window.requestCount()).isEqualTo(1);
            assertThat(window.toString()).doesNotContain("private-metadata-failure", "private-cause", "private-response");
            assertThat(closed.get()).isEqualTo(1);
            transport.verify();
        });
    }
    private static Object readMetadata(ClientHttpResponse response, String stage) throws IOException {
        return switch (stage) {
            case "status" -> response.getStatusCode();
            case "text" -> response.getStatusText();
            case "headers" -> response.getHeaders();
            default -> throw new IllegalArgumentException(stage);
        };
    }

}
