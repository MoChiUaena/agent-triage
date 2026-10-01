package io.github.mochiuaena.triage.tools;

import io.github.mochiuaena.triage.domain.TriageModel.Scenario;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import java.time.Instant;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class ServiceRegistryTest {
    private final ObservationSource live = new ObservationSource("LIVE", "http://127.0.0.1:19082");
    private ServiceRegistry.Config config(String id, String origin) {
        return new ServiceRegistry.Config(id, "结算服务", "stock-service", "商品服务", origin,
            ServiceRegistry.Protocol.OBSERVATIONS_V1, 15, false);
    }

    @Test void oldObservationOriginAndDefaultServiceRemainCompatible() {
        var registry = new ServiceRegistry(live, List.of());
        assertThat(registry.defaultTarget().baseUrl().getPort()).isEqualTo(19082);
        assertThat(registry.defaultTarget().labEnabled()).isTrue();
        assertThat(registry.views()).extracting(ServiceRegistry.View::id).containsExactly("order-service");
    }

    @Test void bindsServerConfigurationAndFreezesServiceSpecificLimits() {
        var env = new MockEnvironment().withProperty("triage.services[0].id", "checkout-service")
            .withProperty("triage.services[0].downstream-id", "stock-service")
            .withProperty("triage.services[0].base-url", "http://127.0.0.1:19092")
            .withProperty("triage.services[0].max-window-minutes", "5");
        var registry = new ServiceRegistry(live, env);
        var target = registry.require("checkout-service");
        var context = new ToolContext("checkout-service", 5, Scenario.OBSERVED, Instant.now(), target);
        assertThat(registry.freeze(context).target()).isEqualTo(target);
        assertThatThrownBy(() -> new ToolContext("checkout-service", 6, Scenario.OBSERVED, Instant.now(), target))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> registry.require("order-service")).isInstanceOf(IllegalArgumentException.class);
        assertThat(target.labEnabled()).isFalse();
        assertThat(target.protocol()).isEqualTo(ServiceRegistry.Protocol.OBSERVATIONS_V1);
    }

    @Test void rejectsDuplicateIdsUnsafeOriginsAndUnapprovedControls() {
        var good = config("checkout-service", "http://127.0.0.1:19092");
        assertThatThrownBy(() -> new ServiceRegistry(live, List.of(good, good))).isInstanceOf(IllegalArgumentException.class);
        for (String url : List.of("https://127.0.0.1:19092", "http://example.com:80", "http://127.0.0.1",
            "http://user:pass@127.0.0.1:19092", "http://127.0.0.1:19092?url=x",
            "http://127.0.0.1:19092/petclinic/../admin", "http://127.0.0.1:19092/%2e%2e/admin",
            "http://127.0.0.1:19092//admin", "http://127.0.0.1:19092/petclinic;admin"))
            assertThatThrownBy(() -> new ServiceRegistry(live, List.of(config("checkout-service", url))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ServiceRegistry(live, List.of(new ServiceRegistry.Config("checkout-service", null,
            "stock-service", null, "http://127.0.0.1:19092", ServiceRegistry.Protocol.OBSERVATIONS_V1, 15, true))))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ServiceRegistry(new ObservationSource("SYNTHETIC", "http://127.0.0.1:19082"), List.of(good)))
            .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void registeredServiceCanUseAPlainLoopbackContextPath() {
        var registry = new ServiceRegistry(live, List.of(config("checkout-service", "http://127.0.0.1:19092/petclinic/")));
        assertThat(registry.defaultTarget().baseUrl().getPath()).isEqualTo("/petclinic/");
        assertThatThrownBy(() -> new ObservationSource("LIVE", "http://127.0.0.1:19092/petclinic"))
            .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void registeredObservationTokenMustHaveTheRequiredFormat() {
        var env = new MockEnvironment().withProperty("triage.services[0].id", "checkout-service")
            .withProperty("triage.services[0].downstream-id", "stock-service")
            .withProperty("triage.services[0].base-url", "http://127.0.0.1:19092")
            .withProperty("triage.services[0].access-token", "short");
        assertThatThrownBy(() -> new ServiceRegistry(live, env)).isInstanceOf(IllegalArgumentException.class)
            .hasMessageNotContaining("short");
    }
    @Test void observationTokenCannotBeSentToLegacyLabRoutes() {
        var lab = new ServiceRegistry.Config("order-service", "订单服务", "inventory-service", "库存服务",
            "http://127.0.0.1:19092", ServiceRegistry.Protocol.LAB, 15, true, false,
            "lab_token_DDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDD");
        assertThatThrownBy(() -> new ServiceRegistry(live, List.of(lab))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void allowsOnlyDatabaseV2ToReadTheFixedSecondaryEndpoint() {
        var alias = new ServiceRegistry.Config("petclinic-db", "Petclinic DB", "petclinic-h2", "H2",
            "http://127.0.0.1:18461", ServiceRegistry.Protocol.DATABASE_V2, 15, false, true);
        assertThat(new ServiceRegistry(live, List.of(alias)).defaultTarget().databaseAlias()).isTrue();
        var invalid = new ServiceRegistry.Config("petclinic-http", "Petclinic", "none", "none",
            "http://127.0.0.1:18461", ServiceRegistry.Protocol.OBSERVATIONS_V3, 15, false, true);
        assertThatThrownBy(() -> new ServiceRegistry(live, List.of(invalid))).isInstanceOf(IllegalArgumentException.class);
    }
}
