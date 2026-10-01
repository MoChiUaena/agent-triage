package org.springframework.samples.petclinic.triage;

import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.net.URI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.ResourceAccessException;

/** Used only by the isolated public-project acceptance run. */
@RestController
@ConditionalOnProperty(name = "triage.verification.enabled", havingValue = "true")
public class RestTriageVerification {
    private final RestClient downstream;
    public RestTriageVerification(RestClient.Builder builder,
            @Value("${triage.verification.downstream-base-url:http://127.0.0.1:1}") URI base) {
        if (!"http".equals(base.getScheme()) || !"127.0.0.1".equals(base.getHost()) || base.getPort() < 1
            || base.getPort() > 65535 || base.getUserInfo() != null || base.getQuery() != null || base.getFragment() != null
            || !(base.getPath().isEmpty() || "/".equals(base.getPath()))) throw new IllegalArgumentException("Use the isolated loopback downstream");
        var factory = new SimpleClientHttpRequestFactory(); factory.setConnectTimeout(150); factory.setReadTimeout(150);
        downstream = builder.requestFactory(factory).baseUrl(base.toString()).build();
    }
    @GetMapping("/api/triage-verification/error")
    public void fail(@RequestHeader(value = "X-Triage-Lab", required = false) String header, HttpServletRequest request) {
        local(header, request);
        ProbeFailure.fail();
    }
    @GetMapping("/api/triage-verification/timeout")
    public ResponseEntity<Void> timeout(@RequestHeader(value = "X-Triage-Lab", required = false) String header, HttpServletRequest request) {
        local(header, request);
        try { downstream.get().uri("/delay").retrieve().toBodilessEntity(); return ResponseEntity.noContent().build(); }
        catch (ResourceAccessException failure) { return ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT).build(); }
    }
    private static void local(String header, HttpServletRequest request) {
        try {
            if ("1".equals(header) && InetAddress.getByName(request.getRemoteAddr()).isLoopbackAddress()) {
                return;
            }
        } catch (Exception ignored) { /* Deny malformed or non-loopback requests. */ }
        throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    }

    static final class ProbeFailure {
        static void fail() { throw new IllegalStateException("verification-only failure"); }
    }
}
