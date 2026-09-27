package io.github.mochiuaena.triage.execution;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;
import java.time.Duration;

@Validated
@ConfigurationProperties("triage")
public record ExecutionLimits(@Min(1) @Max(10) int maxToolCalls,
                              @NotNull Duration toolTimeout, @NotNull Duration runTimeout) {
    public ExecutionLimits {
        if (toolTimeout == null || toolTimeout.toMillis() < 1 || toolTimeout.compareTo(Duration.ofSeconds(10)) > 0)
            throw new IllegalArgumentException("Tool timeout must be 1ms..10s");
        if (runTimeout == null || runTimeout.toMillis() < 1 || runTimeout.compareTo(Duration.ofSeconds(120)) > 0)
            throw new IllegalArgumentException("Run timeout must be 1ms..120s");
    }
}
