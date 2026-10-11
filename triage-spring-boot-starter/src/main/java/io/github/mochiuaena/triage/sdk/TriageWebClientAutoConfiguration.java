package io.github.mochiuaena.triage.sdk;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.reactive.function.client.WebClientCustomizer;
import org.springframework.context.annotation.Bean;

@AutoConfiguration(after = TriageObservationAutoConfiguration.class,
    afterName = "org.springframework.boot.autoconfigure.web.reactive.function.client.WebClientAutoConfiguration")
@ConditionalOnClass(name = "org.springframework.web.reactive.function.client.WebClient")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(prefix = "triage.sdk", name = "enabled", havingValue = "true")
@ConditionalOnProperty(prefix = "triage.sdk", name = "kind", havingValue = "HTTP", matchIfMissing = true)
@ConditionalOnBean(TriageObservationProperties.class)
public class TriageWebClientAutoConfiguration {
    @Bean WebClientCustomizer triageWebClientCustomizer(TriageObservationProperties properties) {
        return new TriageWebClientCustomizer(properties);
    }
}
