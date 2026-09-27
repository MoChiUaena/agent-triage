package io.github.mochiuaena.triage.model;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import java.net.URI;
import java.time.Duration;

@ConfigurationProperties("triage.model")
public record ModelSettings(@DefaultValue("https://api.deepseek.com") String baseUrl,
                            @DefaultValue("") String apiKey,
                            @DefaultValue("deepseek-flash") String name,
                            @DefaultValue("20s") Duration timeout,
                            @DefaultValue("4") int maxRounds,
                            @DefaultValue("1600") int maxTokens) {
    public ModelSettings {
        if (timeout == null || timeout.toMillis() < 1 || timeout.compareTo(Duration.ofSeconds(60)) > 0)
            throw new IllegalArgumentException("Model timeout must be 1ms..60s");
        if (maxRounds < 1 || maxRounds > 8) throw new IllegalArgumentException("Model rounds must be 1..8");
        if (maxTokens < 256 || maxTokens > 4096) throw new IllegalArgumentException("Model output tokens must be 256..4096");
    }

    public void requireCredentials() {
        if (apiKey == null || apiKey.isBlank()) throw new IllegalArgumentException("MODEL mode requires TRIAGE_MODEL_API_KEY");
        if (name == null || !name.matches("[A-Za-z0-9._:/-]{1,120}")) throw new IllegalArgumentException("Invalid TRIAGE_MODEL_NAME");
        URI uri;
        try { uri = URI.create(baseUrl); }
        catch (RuntimeException e) { throw new IllegalArgumentException("Invalid TRIAGE_MODEL_BASE_URL"); }
        boolean localHttp = "http".equals(uri.getScheme()) &&
            java.util.Set.of("localhost", "127.0.0.1", "[::1]").contains(uri.getHost() == null ? "" : uri.getHost());
        if (uri.getHost() == null || (!"https".equals(uri.getScheme()) && !localHttp)
            || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null)
            throw new IllegalArgumentException("Model URL must be HTTPS (or loopback HTTP), without credentials, query or fragment");
    }

    @Override public String toString() { return "ModelSettings[credentials=redacted]"; }
}
