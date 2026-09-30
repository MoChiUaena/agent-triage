package io.github.mochiuaena.triage.sdk;

import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.time.Instant;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.beans.factory.ObjectProvider;
import static org.springframework.http.HttpStatus.FORBIDDEN;
import static org.springframework.http.HttpStatus.NOT_FOUND;

@RestController
final class TriageObservationsEndpoint {
    private final ObservationRecorder recorder;
    private final ObjectProvider<TriageJpaObserver> jpa;
    TriageObservationsEndpoint(ObservationRecorder recorder, ObjectProvider<TriageJpaObserver> jpa) { this.recorder = recorder; this.jpa = jpa; }
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
    }
}
