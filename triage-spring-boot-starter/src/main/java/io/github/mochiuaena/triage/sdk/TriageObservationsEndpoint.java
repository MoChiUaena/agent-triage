package io.github.mochiuaena.triage.sdk;

import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.List;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.beans.factory.ObjectProvider;
import static org.springframework.http.HttpStatus.FORBIDDEN;
import static org.springframework.http.HttpStatus.NOT_FOUND;

@RestController
final class TriageObservationsEndpoint {
    private final ObservationRecorder recorder;
    private final ObjectProvider<TriageJpaObserver> jpa;
    private final List<byte[]> accessTokens;
    TriageObservationsEndpoint(ObservationRecorder recorder, ObjectProvider<TriageJpaObserver> jpa,
                               TriageObservationProperties properties) {
        this.recorder = recorder; this.jpa = jpa;
        accessTokens = properties.getObservationAccessToken() == null ? List.of()
            : properties.getObservationPreviousToken() == null
                ? List.of(properties.getObservationAccessToken().getBytes(StandardCharsets.US_ASCII))
                : List.of(properties.getObservationAccessToken().getBytes(StandardCharsets.US_ASCII),
                    properties.getObservationPreviousToken().getBytes(StandardCharsets.US_ASCII));
    }
    @GetMapping("/triage/observations")
    public Object observations(@RequestParam int windowMinutes, @RequestParam Instant endTime, HttpServletRequest request) {
        local(request);
        return recorder.snapshot(windowMinutes, endTime);
    }
    @GetMapping("/triage/endpoint-observations")
    public Object endpoints(@RequestParam int windowMinutes, @RequestParam Instant endTime,
                            @RequestParam(required = false) String endpointId, HttpServletRequest request) {
        local(request);
        return recorder.endpointSnapshot(windowMinutes, endTime, endpointId);
    }
    @GetMapping("/triage/database-observations")
    public Object database(@RequestParam int windowMinutes, @RequestParam Instant endTime, HttpServletRequest request) {
        local(request);
        TriageJpaObserver observer = jpa.getIfAvailable();
        if (observer == null) throw new ResponseStatusException(NOT_FOUND, "JPA observations are disabled");
        return observer.snapshot(windowMinutes, endTime);
    }
    private void local(HttpServletRequest request) {
        try {
            if (!InetAddress.getByName(request.getRemoteAddr()).isLoopbackAddress()) throw new IllegalArgumentException();
        } catch (Exception e) { throw new ResponseStatusException(FORBIDDEN, "Observations are available to loopback clients only"); }
        if (!accessTokens.isEmpty()) {
            var headers = request.getHeaders("X-Triage-Observation-Token");
            if (!headers.hasMoreElements()) throw new ResponseStatusException(FORBIDDEN, "Observation access denied");
            String provided = headers.nextElement();
            if (headers.hasMoreElements() || provided == null || provided.length() > 128)
                throw new ResponseStatusException(FORBIDDEN, "Observation access denied");
            byte[] supplied = provided.getBytes(StandardCharsets.UTF_8);
            boolean accepted = false;
            for (byte[] expected : accessTokens) accepted |= MessageDigest.isEqual(expected, supplied);
            if (!accepted) throw new ResponseStatusException(FORBIDDEN, "Observation access denied");
        }
    }
}
