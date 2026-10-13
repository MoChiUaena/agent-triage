package io.github.mochiuaena.triage.api;

import io.github.mochiuaena.triage.tools.ServiceRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Opt-in controls for the packaged, local catalog checkout sample. */
@Component
final class CheckoutDemo {
    private final boolean enabled;
    private final ServiceRegistry registry;

    CheckoutDemo(@Value("${triage.demo.checkout-enabled:false}") boolean enabled, ServiceRegistry registry) {
        this.enabled = enabled;
        this.registry = registry;
    }

    boolean enabledFor(ServiceRegistry.Target target) {
        if (!enabled || !"catalog-service".equals(target.info().id())
            || target.protocol() != ServiceRegistry.Protocol.OBSERVATIONS_V5) return false;
        try {
            ServiceRegistry.Target order = registry.require("order-service");
            return order.protocol() == ServiceRegistry.Protocol.LAB && order.labEnabled();
        } catch (IllegalArgumentException e) { return false; }
    }

    ServiceRegistry.Target inventoryControls() { return registry.require("order-service"); }
}
