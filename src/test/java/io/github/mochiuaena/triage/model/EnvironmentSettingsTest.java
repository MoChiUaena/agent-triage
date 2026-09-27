package io.github.mochiuaena.triage.model;

import io.github.mochiuaena.triage.execution.ExecutionLimits;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import java.time.Duration;
import static org.assertj.core.api.Assertions.*;

class EnvironmentSettingsTest {
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({ExecutionLimits.class, ModelSettings.class, RuntimeSettings.class})
    static class BindingConfiguration {}

    @Test void documentedEnvironmentNamesOverrideYamlDefaults() {
        new ApplicationContextRunner().withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(BindingConfiguration.class)
            .withPropertyValues("TRIAGE_MODE=MODEL", "TRIAGE_MAX_TOOL_CALLS=6", "TRIAGE_RUN_TIMEOUT=90s",
                "TRIAGE_TOOL_TIMEOUT=4s", "TRIAGE_MODEL_MAX_ROUNDS=5", "TRIAGE_MODEL_TIMEOUT=30s", "TRIAGE_MODEL_MAX_TOKENS=2000")
            .run(context -> {
                assertThat(context).hasNotFailed();
                ExecutionLimits limits = context.getBean(ExecutionLimits.class);
                ModelSettings model = context.getBean(ModelSettings.class);
                assertThat(limits.maxToolCalls()).isEqualTo(6);
                assertThat(limits.runTimeout()).isEqualTo(Duration.ofSeconds(90));
                assertThat(limits.toolTimeout()).isEqualTo(Duration.ofSeconds(4));
                assertThat(model.maxRounds()).isEqualTo(5);
                assertThat(model.timeout()).isEqualTo(Duration.ofSeconds(30));
                assertThat(model.maxTokens()).isEqualTo(2000);
                assertThat(context.getBean(RuntimeSettings.class).mode()).isEqualTo(RuntimeSettings.Mode.MODEL);
            });
    }
}
