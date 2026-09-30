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
        this.baseUrl = origin(url);
    }

    public static URI origin(String url) {
        return loopback(url, false);
    }

    public static URI registeredBase(String url) {
        return loopback(url, true);
    }

    private static URI loopback(String url, boolean contextPath) {
        if (url == null) throw new IllegalArgumentException("Observation origin is required");
        URI baseUrl = URI.create(url);
        String host = baseUrl.getHost();
        String path = baseUrl.getRawPath();
        boolean allowedPath = path == null || path.isEmpty() || "/".equals(path)
            || contextPath && path.length() <= 160 && path.matches("/[A-Za-z0-9_-]{1,40}(?:/[A-Za-z0-9_-]{1,40})*/?");
        if (!"http".equals(baseUrl.getScheme()) || host == null
            || !Set.of("127.0.0.1", "localhost", "[::1]").contains(host)
            || baseUrl.getPort() < 1 || baseUrl.getPort() > 65535
            || baseUrl.getUserInfo() != null || baseUrl.getQuery() != null || baseUrl.getFragment() != null
            || !allowedPath)
            throw new IllegalArgumentException("Observation base URL must be loopback HTTP with an explicit port and a plain context path");
        return baseUrl;
    }

    public Kind kind() { return kind; }
    public boolean synthetic() { return kind == Kind.SYNTHETIC; }
    public URI baseUrl() { return baseUrl; }
}
