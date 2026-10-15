package io.github.mochiuaena.triage.sdk;

import feign.Capability;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import static org.assertj.core.api.Assertions.*;

class FeignAutoConfigurationTest {
    private final WebApplicationContextRunner runner = new WebApplicationContextRunner().withConfiguration(
        AutoConfigurations.of(TriageObservationAutoConfiguration.class, TriageFeignAutoConfiguration.class));

    @Test void activeHttpServiceExposesOneFeignCapability() {
        runner.withPropertyValues("triage.sdk.enabled=true", "triage.sdk.service-id=catalog-service",
            "triage.sdk.downstream-id=inventory-service", "triage.sdk.downstream-base-url=http://127.0.0.1:18084")
            .run(context -> assertThat(context).hasNotFailed().hasSingleBean(Capability.class));
    }

    @Test void absentFeignDoesNotPreventTheStarterFromLoading() {
        runner.withClassLoader(new FilteredClassLoader(Capability.class))
            .withPropertyValues("triage.sdk.enabled=true", "triage.sdk.service-id=catalog-service",
                "triage.sdk.downstream-id=inventory-service", "triage.sdk.downstream-base-url=http://127.0.0.1:18084")
            .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(Capability.class));
    }

    @Test void disabledAndInboundOnlyServicesDoNotModifyFeign() {
        runner.run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(Capability.class));
        runner.withPropertyValues("triage.sdk.enabled=true", "triage.sdk.kind=HTTP_REQUESTS",
            "triage.sdk.service-id=catalog-service")
            .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(Capability.class));
    }
}
