package io.github.mochiuaena.inventory;

import org.springframework.stereotype.Component;
import java.util.concurrent.atomic.AtomicReference;

@Component
public class InventoryState {
    public enum Scenario { NORMAL, DOWNSTREAM_TIMEOUT }
    private final AtomicReference<Scenario> scenario = new AtomicReference<>(Scenario.NORMAL);

    public Scenario scenario() { return scenario.get(); }
    public void scenario(Scenario next) { scenario.set(next); }
    public void reset() { scenario.set(Scenario.NORMAL); }
}
