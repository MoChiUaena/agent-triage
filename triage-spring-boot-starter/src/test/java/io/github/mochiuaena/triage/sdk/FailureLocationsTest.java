package io.github.mochiuaena.triage.sdk;

import com.fasterxml.jackson.databind.ObjectMapper;
import example.locations.BusinessFixture;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Proxy;
import java.net.SocketTimeoutException;
import java.net.URLClassLoader;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.*;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;

class FailureLocationsTest {
    private TriageObservationProperties properties() {
        var properties = ObservationRecorderTest.properties(); properties.setEndpointObservations(true);
        properties.setExceptionLocations(true); properties.setApplicationPackages(List.of("example.locations")); properties.validate();
        return properties;
    }
    @Test void featureIsOffByDefaultAndNeedsExplicitPackagesAndV3() throws Exception {
        var off = ObservationRecorderTest.properties();
        assertThat(FailureLocations.capture(new RuntimeException("private-message"), off, "REQUEST_EXCEPTION")).isNull();
        off.setExceptionLocations(true);
        assertThatThrownBy(off::validate).isInstanceOf(IllegalArgumentException.class);
        off.setEndpointObservations(true);
        assertThatThrownBy(off::validate).isInstanceOf(IllegalArgumentException.class);
        off.setApplicationPackages(List.of("example/locations"));
        assertThatThrownBy(off::validate).isInstanceOf(IllegalArgumentException.class);
        var recorder = new ObservationRecorder(properties());
        recorder.recordHttp(1, 1, true, true, "fixture-trace");
        var json = new ObjectMapper().findAndRegisterModules();
        assertThat(json.writeValueAsString(recorder.endpointSnapshot(5, Instant.now(), null))).doesNotContain("failureLocation");
    }
    @Test void caughtHttpTimeoutKeepsOnlyConfiguredBusinessFramesAndLeavesV1Unchanged() throws Exception {
        var properties = properties(); var recorder = new ObservationRecorder(properties);
        RestClient.Builder builder = RestClient.builder(); new TriageRestClientCustomizer(properties).customize(builder);
        var mock = MockRestServiceServer.bindTo(builder).build();
        String origin = properties.getDownstreamBaseUrl().toString();
        mock.expect(requestTo(origin + "/private-item?token=private-query")).andExpect(method(HttpMethod.GET))
            .andRespond(request -> { throw new SocketTimeoutException("private-exception-message"); });
        var client = builder.build(); var response = new MockHttpServletResponse();
        new TriageRequestFilter(recorder).doFilter(new MockHttpServletRequest("GET", "/api/private-item"), response,
            (req, res) -> BusinessFixture.lookup(() -> {
                try { client.get().uri(origin + "/private-item?token=private-query").retrieve().body(String.class); }
                catch (ResourceAccessException e) { response.setStatus(504); }
            }));
        var value = (ObservationRecorder.EndpointWindow) recorder.endpointSnapshot(5, Instant.now(), null);
        var location = value.errors().getFirst().failureLocation();
        assertThat(location.kind()).isEqualTo("HTTP_CLIENT_FAILURE");
        assertThat(location.exceptionTypes()).containsExactly("java.net.SocketTimeoutException");
        assertThat(location.frames()).extracting(FailureLocations.Frame::className).containsExactly("example.locations.BusinessFixture");
        assertThat(location.frames().getFirst().methodName()).isEqualTo("lookup");
        assertThat(location.frames().getFirst().lineNumber()).isPositive();
        assertThat(value.toString()).doesNotContain("private-query", "private-item", "private-exception-message");
        String legacy = new ObjectMapper().findAndRegisterModules().writeValueAsString(recorder.snapshot(5, value.windowEnd()));
        assertThat(legacy).doesNotContain("BusinessFixture", "failureLocation", "SocketTimeoutException");
        assertThat(TriageRequestFilter.CURRENT.get()).isNull(); mock.verify();
    }
    @Test void requestExceptionsBoundCausesAndFramesAndRejectFilePaths() {
        var error = new RuntimeException("private-message"); var cause = new IllegalArgumentException("private-cause");
        error.initCause(cause); cause.initCause(error);
        var frames = new ArrayList<StackTraceElement>();
        frames.add(new StackTraceElement("example.locationsExtra.Secret", "open", "Secret.java", 20));
        frames.add(new StackTraceElement("example.locations.BusinessFixture", "lookup", "../private-file.java", -1));
        for (int i = 0; i < 20; i++) frames.add(new StackTraceElement("example.locations.BusinessFixture", "step" + i, "BusinessFixture.java", 10 + i));
        error.setStackTrace(frames.toArray(StackTraceElement[]::new)); cause.setStackTrace(new StackTraceElement[0]);
        var value = FailureLocations.capture(error, properties(), "REQUEST_EXCEPTION");
        assertThat(value.frames()).hasSize(8); assertThat(value.truncated()).isTrue();
        assertThat(value.frames().getFirst().fileName()).isNull(); assertThat(value.frames().getFirst().lineNumber()).isNull();
        assertThat(value.toString()).doesNotContain("private-message", "private-cause", "../private-file", "locationsExtra");
        assertThat(value.exceptionTypes()).containsExactly("java.lang.RuntimeException", "java.lang.IllegalArgumentException");
    }
    @Test void escapingRequestExceptionIsRecordedBeforeThreadContextIsCleared() {
        var recorder = new ObservationRecorder(properties());
        var error = new IllegalStateException("private-message");
        error.setStackTrace(new StackTraceElement[]{new StackTraceElement("example.locations.BusinessFixture", "lookup", "BusinessFixture.java", 5)});
        assertThatThrownBy(() -> new TriageRequestFilter(recorder).doFilter(new MockHttpServletRequest("GET", "/api/failure"),
            new MockHttpServletResponse(), (req, res) -> { throw error; })).isSameAs(error);
        var value = (ObservationRecorder.EndpointWindow) recorder.endpointSnapshot(5, Instant.now(), null);
        assertThat(value.errors().getFirst().failureLocation().kind()).isEqualTo("REQUEST_EXCEPTION");
        assertThat(value.errors().getFirst().failureLocation().frames()).hasSize(1);
        assertThat(TriageRequestFilter.CURRENT.get()).isNull();
    }
    @Test void handledMvcExceptionRetainsItsLocationAndLeavesResolutionToTheApplication() throws Exception {
        var recorder = new ObservationRecorder(properties());
        var observer = new TriageHandledExceptionObserver(recorder);
        var resolvers = new ArrayList<org.springframework.web.servlet.HandlerExceptionResolver>();
        observer.extendHandlerExceptionResolvers(resolvers);
        assertThat(resolvers).containsExactly(observer);
        var error = new IllegalStateException("private-message");
        error.setStackTrace(new StackTraceElement[]{new StackTraceElement("example.locations.BusinessFixture", "lookup", "BusinessFixture.java", 5)});
        var response = new MockHttpServletResponse();
        new TriageRequestFilter(recorder).doFilter(new MockHttpServletRequest("GET", "/api/handled-error"), response,
            (req, res) -> {
                assertThat(observer.resolveException((jakarta.servlet.http.HttpServletRequest) req,
                    (jakarta.servlet.http.HttpServletResponse) res, new Object(), error)).isNull();
                ((jakarta.servlet.http.HttpServletResponse) res).setStatus(500);
            });
        var window = (ObservationRecorder.EndpointWindow) recorder.endpointSnapshot(5, Instant.now(), null);
        assertThat(window.errors().getFirst().failureLocation().frames()).extracting(FailureLocations.Frame::className)
            .containsExactly("example.locations.BusinessFixture");
        assertThat(TriageRequestFilter.CURRENT.get()).isNull();
    }
    @Test void requestExceptionVersionsOnlyFramesOfTheSelectedMvcClass(@TempDir Path root) throws Exception {
        Path sources = Files.createDirectory(root.resolve("sources")), classes = Files.createDirectory(root.resolve("classes"));
        Path file = sources.resolve("Handler.java");
        Files.writeString(file, "package example.locations; public class Handler { public void fail() { Helper.fail(); } } "
            + "class Helper { static void fail() { throw new IllegalStateException(\"private-message\"); } }");
        assertThat(ToolProvider.getSystemJavaCompiler().run(null, null, null, "-proc:none", "-d", classes.toString(), file.toString())).isZero();
        assertThat(SourceBuildManifest.generate(sources, classes)).isEqualTo(2);
        var configured = properties(); configured.setSourceVersionChecks(true); configured.validate();
        try (var loader = new URLClassLoader(new java.net.URL[]{classes.toUri().toURL()}, null)) {
            Class<?> selected = loader.loadClass("example.locations.Handler");
            var thrown = catchThrowable(() -> selected.getMethod("fail").invoke(selected.getConstructor().newInstance()));
            assertThat(thrown).isInstanceOf(java.lang.reflect.InvocationTargetException.class);
            Throwable failure = thrown.getCause();
            var location = FailureLocations.capture(failure, configured, "REQUEST_EXCEPTION", selected);
            String expected = SourceBuildManifest.digest(Files.readAllBytes(file));
            assertThat(location.frames()).filteredOn(frame -> frame.className().equals(selected.getName()))
                .extracting(FailureLocations.Frame::sourceHash).containsExactly(expected);
            assertThat(location.frames()).filteredOn(frame -> frame.className().equals("example.locations.Helper"))
                .extracting(FailureLocations.Frame::sourceHash).containsExactly((String) null);
            assertThat(FailureLocations.capture(failure, configured, "REQUEST_EXCEPTION").frames())
                .extracting(FailureLocations.Frame::sourceHash).containsOnlyNulls();
            configured.setSourceVersionChecks(false);
            assertThat(FailureLocations.capture(failure, configured, "REQUEST_EXCEPTION", selected).frames())
                .extracting(FailureLocations.Frame::sourceHash).containsOnlyNulls();
            configured.setSourceVersionChecks(true);
            var loaded = new AtomicReference<Class<?>[]>();
            var runtime = (Instrumentation) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Instrumentation.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("getAllLoadedClasses")) return loaded.get();
                    throw new UnsupportedOperationException(method.getName());
                });
            Class<?> helper = loader.loadClass("example.locations.Helper");
            try (var duplicate = new URLClassLoader(new java.net.URL[]{classes.toUri().toURL()}, null)) {
                RuntimeClassAgent.premain("", runtime);
                loaded.set(new Class<?>[]{selected, helper});
                var resolved = FailureLocations.capture(failure, configured, "REQUEST_EXCEPTION", selected);
                assertThat(resolved.frames()).extracting(FailureLocations.Frame::sourceHash).containsExactly(expected, expected);
                loaded.set(new Class<?>[]{selected, helper, duplicate.loadClass("example.locations.Helper")});
                var ambiguousHelper = FailureLocations.capture(failure, configured, "REQUEST_EXCEPTION", selected);
                assertThat(ambiguousHelper.frames()).extracting(FailureLocations.Frame::sourceHash).containsExactly((String) null, expected);
                loaded.set(new Class<?>[]{selected, duplicate.loadClass("example.locations.Handler"), helper});
                var ambiguousHandler = FailureLocations.capture(failure, configured, "REQUEST_EXCEPTION", selected);
                assertThat(ambiguousHandler.frames()).extracting(FailureLocations.Frame::sourceHash).containsExactly(expected, (String) null);
            } finally { RuntimeClassAgent.premain("", null); }
            failure.setStackTrace(new StackTraceElement[]{new StackTraceElement("another-loader", null, null,
                selected.getName(), "fail", "Handler.java", 1)});
            assertThat(FailureLocations.capture(failure, configured, "REQUEST_EXCEPTION", selected).frames().getFirst().sourceHash()).isNull();
            assertThat(location.toString()).doesNotContain("private-message", root.toString());
        }
    }
}
