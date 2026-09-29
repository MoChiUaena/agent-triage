package io.github.mochiuaena.triage.sdk;

import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.time.Instant;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.FORBIDDEN;

@RestController
final class TriageObservationsEndpoint {
    private final ObservationRecorder recorder;
    TriageObservationsEndpoint(ObservationRecorder recorder) { this.recorder = recorder; }
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
    private void local(HttpServletRequest request) {
        try {
            if (!InetAddress.getByName(request.getRemoteAddr()).isLoopbackAddress()) throw new IllegalArgumentException();
        } catch (Exception e) { throw new ResponseStatusException(FORBIDDEN, "Observations are available to loopback clients only"); }
    }
}
