package io.github.mochiuaena.triage.tools;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.util.Set;

@Component
public class ObservationSource {
    public enum Kind { SYNTHETIC, LIVE }
    private final Kind kind;
    private final URI baseUrl;

    public ObservationSource(@Value("${triage.observation.source:SYNTHETIC}") String source,
                             @Value("${triage.observation.base-url:http://127.0.0.1:18082}") String url) {
        this.kind = Kind.valueOf(source);
        this.baseUrl = URI.create(url);
        String host = baseUrl.getHost();
        if (!"http".equals(baseUrl.getScheme()) || host == null
            || !Set.of("127.0.0.1", "localhost", "[::1]").contains(host)
            || baseUrl.getPort() < 1 || baseUrl.getPort() > 65535
            || baseUrl.getUserInfo() != null || baseUrl.getQuery() != null || baseUrl.getFragment() != null
            || !(baseUrl.getPath().isEmpty() || "/".equals(baseUrl.getPath())))
            throw new IllegalArgumentException("Observation base URL must be a loopback HTTP origin with an explicit port");
    }

    public Kind kind() { return kind; }
    public boolean synthetic() { return kind == Kind.SYNTHETIC; }
    public URI baseUrl() { return baseUrl; }
}
