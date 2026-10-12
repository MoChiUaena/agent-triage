package io.github.mochiuaena.triage.sdk;

import feign.Capability;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;

@AutoConfiguration(after = TriageObservationAutoConfiguration.class)
@ConditionalOnClass(name = "feign.Capability")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(prefix = "triage.sdk", name = "enabled", havingValue = "true")
@ConditionalOnProperty(prefix = "triage.sdk", name = "kind", havingValue = "HTTP", matchIfMissing = true)
@ConditionalOnBean(TriageObservationProperties.class)
public class TriageFeignAutoConfiguration {
    @Bean Capability triageFeignCapability(TriageObservationProperties properties) {
        return new TriageFeignCapability(properties);
    }
}
