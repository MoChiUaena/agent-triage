package io.github.mochiuaena.triage.sdk;

import java.lang.ref.Reference;
import java.lang.ref.WeakReference;
import java.lang.reflect.Proxy;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

class ResourceLifecycleTest {
    @Test void retainedCompletedSnapshotDoesNotPinTheBusinessClassLoader() {
        var retained = completedSnapshot();
        try {
            await().atMost(Duration.ofSeconds(3)).pollInterval(Duration.ofMillis(50)).untilAsserted(() -> {
                System.gc();
                assertThat(retained.loader().get()).isNull();
            });
            retained.snapshot().wrap((Runnable) () -> assertThat(TriageRequestFilter.CURRENT.get()).isNull()).run();
        } finally { Reference.reachabilityFence(retained.snapshot()); }
    }

    private record Retained(TriageObservationContext.Snapshot snapshot, WeakReference<ClassLoader> loader) {}
    private static Retained completedSnapshot() {
        var context = new TriageRequestFilter.Context();
        var loader = new ClassLoader(null) {};
        context.handlerClass = Proxy.newProxyInstance(loader, new Class<?>[]{Runnable.class}, (proxy, method, args) -> null).getClass();
        TriageRequestFilter.CURRENT.set(context);
        try {
            var snapshot = TriageObservationContext.capture();
            context.finish();
            return new Retained(snapshot, new WeakReference<>(loader));
        } finally { TriageRequestFilter.CURRENT.remove(); }
    }
}
