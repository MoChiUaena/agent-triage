package org.springframework.samples.petclinic.triage;

import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Used only by the isolated public-project acceptance run. */
@RestController
@ConditionalOnProperty(name = "triage.verification.enabled", havingValue = "true")
public class RestTriageVerification {
    @GetMapping("/api/triage-verification/error")
    public void fail(@RequestHeader(value = "X-Triage-Lab", required = false) String header, HttpServletRequest request) {
        try {
            if ("1".equals(header) && InetAddress.getByName(request.getRemoteAddr()).isLoopbackAddress()) {
                ProbeFailure.fail();
                return;
            }
        } catch (IllegalStateException failure) { throw failure; }
        catch (Exception ignored) { /* Deny malformed or non-loopback requests. */ }
        throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    }

    static final class ProbeFailure {
        static void fail() { throw new IllegalStateException("verification-only failure"); }
    }
}
