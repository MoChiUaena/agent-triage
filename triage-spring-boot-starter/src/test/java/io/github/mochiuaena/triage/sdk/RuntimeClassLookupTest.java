package io.github.mochiuaena.triage.sdk;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class RuntimeClassLookupTest {
    @Test void limitsAutomaticClassScansDuringAnExceptionBurst() {
        var budget = new RuntimeClassLookup.Budget(20);
        for (int i = 0; i < 20; i++) assertThat(budget.take(1_000_000L)).isTrue();
        assertThat(budget.take(1_000_000L)).isFalse();
        assertThat(budget.take(1_001_000_000L)).isTrue();
    }
}
