package io.github.mochiuaena.triage.sdk;

import java.util.HashSet;
import java.util.Set;

/** Only initial traffic may establish reuse; late OS port reallocation is not evidence. */
final class HealthyConnectionProbe {
    private final Set<Integer> ports = new HashSet<>();
    private int requests;
    private boolean reused;
    synchronized void accept(int port) {
        if (reused || ++requests > 32) return;
        if (!ports.add(port)) reused = true;
    }
    synchronized boolean reused() { return reused; }
}
