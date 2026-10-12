package io.github.mochiuaena.triage.sdk;

import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import static org.assertj.core.api.Assertions.*;

class MultiDownstreamPropertiesTest {
    private static TriageObservationProperties properties(TriageObservationProperties.Downstream... downstreams) {
        var properties = new TriageObservationProperties();
        properties.setServiceId("catalog-service");
        properties.setDownstreams(List.of(downstreams));
        return properties;
    }

    @Test void distinctConfiguredOriginsResolveToTheirOwnIdentity() {
        var properties = properties(new TriageObservationProperties.Downstream("inventory-service", URI.create("http://127.0.0.1:18084")),
            new TriageObservationProperties.Downstream("payment-service", URI.create("https://payments.example:443")));
        assertThatCode(properties::validate).doesNotThrowAnyException();
        assertThat(properties.downstreamFor(URI.create("http://127.0.0.1:18084/items?secret=private"))).isEqualTo("inventory-service");
        assertThat(properties.downstreamFor(URI.create("https://payments.example/pay"))).isEqualTo("payment-service");
        assertThat(properties.downstreamFor(URI.create("http://127.0.0.1:18085/items"))).isNull();
    }

    @Test void rejectsMixedDuplicateAndUnsafeDownstreamConfigurations() {
        var inventory = new TriageObservationProperties.Downstream("inventory-service", URI.create("http://127.0.0.1:18084"));
        var payment = new TriageObservationProperties.Downstream("payment-service", URI.create("http://127.0.0.1:18085"));
        var mixed = properties(inventory, payment);
        mixed.setDownstreamId("legacy-service"); mixed.setDownstreamBaseUrl(URI.create("http://127.0.0.1:18086"));
        assertThatThrownBy(mixed::validate).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties(inventory, inventory).validate()).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties(inventory,
            new TriageObservationProperties.Downstream("other-service", URI.create("http://127.0.0.1:18084/"))).validate())
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties(new TriageObservationProperties.Downstream("payment-service",
            URI.create("https://user:secret@payments.example/private"))).validate()).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties(new TriageObservationProperties.Downstream("bad id",
            URI.create("https://payments.example"))).validate()).isInstanceOf(IllegalArgumentException.class);
        var inbound = properties(inventory); inbound.setKind(TriageObservationProperties.Kind.HTTP_REQUESTS);
        assertThatThrownBy(inbound::validate).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void bootBindsMultipleDownstreamsWithoutLegacyFields() {
        new WebApplicationContextRunner().withConfiguration(AutoConfigurations.of(TriageObservationAutoConfiguration.class))
            .withPropertyValues("triage.sdk.enabled=true", "triage.sdk.service-id=catalog-service",
                "triage.sdk.downstreams[0].id=inventory-service", "triage.sdk.downstreams[0].base-url=http://127.0.0.1:18084",
                "triage.sdk.downstreams[1].id=payment-service", "triage.sdk.downstreams[1].base-url=http://127.0.0.1:18085")
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context.getBean(TriageObservationProperties.class).getDownstreams()).hasSize(2);
            });
    }
}
