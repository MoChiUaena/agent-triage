package io.github.mochiuaena.triage.sdk;

import java.lang.management.ManagementFactory;
import java.util.*;

/** Numeric, bounded timing evidence owned only by the HTTP resource fixture. */
final class HttpResourceTiming implements AutoCloseable {
    enum Scenario { NORMAL, HEADER_TIMEOUT, BODY_TIMEOUT }
    enum Phase { READ_BODY, DELAY_HEADERS, WRITE_HEADERS, WRITE_FIRST_BYTE, DELAY_BODY, WRITE_LAST_BYTE }
    interface MonotonicClock { long nanoTime(); }
    interface CpuClock { long nanoTime(long threadId); }
    record Sample(long requestId, long threadId, Scenario scenario, Phase phase, long requestedNanos,
                  long elapsedNanos, long cpuNanos, long requestElapsedNanos) {
        String line(boolean active) {
            return String.format(Locale.ROOT,
                "RESOURCE_TIMING kind=%s request=%d thread=%d scenario=%s stage=%s requestedMs=%.3f elapsedMs=%.3f cpuNanos=%d requestElapsedMs=%.3f",
                active ? "active" : "recent", requestId, threadId, scenario, phase, millis(requestedNanos), millis(elapsedNanos), cpuNanos,
                millis(requestElapsedNanos));
        }
    }
    record Snapshot(List<Sample> active, List<Sample> recent, long delaySamples, long maxDelayElapsedNanos,
                    long maxDelayOvershootNanos, long maxActiveDelayElapsedNanos, long untrackedRequests,
                    long driverElapsedNanos, long driverMaxLagNanos) {
        String summary() {
            return String.format(Locale.ROOT,
                "driverMaxLagMs=%.3f driverElapsedMs=%.3f timingDelaySamples=%d timingMaxDelayElapsedMs=%.3f timingMaxDelayOvershootMs=%.3f timingMaxActiveDelayElapsedMs=%.3f timingActive=%d timingRecent=%d timingUntrackedRequests=%d",
                millis(driverMaxLagNanos), millis(driverElapsedNanos), delaySamples, millis(maxDelayElapsedNanos),
                millis(maxDelayOvershootNanos), millis(maxActiveDelayElapsedNanos), active.size(), recent.size(), untrackedRequests);
        }
    }
    private final MonotonicClock wall;
    private final CpuClock cpu;
    private final Active[] active = new Active[8];
    private final Sample[] recent = new Sample[64];
    private int nextSample, sampleCount;
    private long sequence, delaySamples, maxDelayElapsedNanos, maxDelayOvershootNanos, untrackedRequests;
    private long driverStartedNanos, driverMaxLagNanos;
    private boolean driverRunning, closed;

    HttpResourceTiming(MonotonicClock wall, CpuClock cpu) { this.wall = wall; this.cpu = cpu; }
    static HttpResourceTiming system() {
        var threads = ManagementFactory.getThreadMXBean();
        // Do not change the VM-wide CPU accounting setting for this optional probe.
        return new HttpResourceTiming(System::nanoTime, threadId -> {
            if (!threads.isThreadCpuTimeSupported() || !threads.isThreadCpuTimeEnabled()) return -1;
            try { return threads.getThreadCpuTime(threadId); }
            catch (UnsupportedOperationException failure) { return -1; }
        });
    }
    synchronized void begin(long threadId, Scenario scenario) {
        if (closed) return;
        for (int index = 0; index < active.length; index++) {
            if (active[index] == null) {
                active[index] = new Active(++sequence, threadId, scenario, wall.nanoTime(), cpu.nanoTime(threadId));
                return;
            }
        }
        untrackedRequests++;
    }
    synchronized void phase(long threadId, Phase phase, long requestedMillis) {
        for (var request : active) if (request != null && request.threadId == threadId) {
            long now = wall.nanoTime(), currentCpu = cpu.nanoTime(threadId);
            remember(sample(request, now, currentCpu));
            request.phase = phase; request.requestedNanos = requestedMillis * 1_000_000L;
            request.phaseStartedNanos = now; request.phaseStartedCpuNanos = currentCpu;
            return;
        }
    }
    synchronized void end(long threadId) {
        for (int index = 0; index < active.length; index++) {
            var request = active[index];
            if (request != null && request.threadId == threadId) {
                remember(sample(request, wall.nanoTime(), cpu.nanoTime(threadId)));
                active[index] = null;
                return;
            }
        }
    }
    synchronized void driverStarted(long startedNanos) { driverStartedNanos = startedNanos; driverRunning = true; }
    synchronized void driverLag(long lagNanos) { driverMaxLagNanos = Math.max(driverMaxLagNanos, lagNanos); }
    synchronized Snapshot snapshot() {
        long now = wall.nanoTime(), maxActiveDelayElapsedNanos = 0;
        var running = new ArrayList<Sample>();
        for (var request : active) if (request != null) {
            var sample = sample(request, now, cpu.nanoTime(request.threadId));
            running.add(sample);
            if (delay(sample.phase())) maxActiveDelayElapsedNanos = Math.max(maxActiveDelayElapsedNanos, sample.elapsedNanos());
        }
        var history = new ArrayList<Sample>(sampleCount);
        for (int index = 0; index < sampleCount; index++)
            history.add(recent[(nextSample - sampleCount + index + recent.length) % recent.length]);
        return new Snapshot(List.copyOf(running), List.copyOf(history), delaySamples, maxDelayElapsedNanos,
            maxDelayOvershootNanos, maxActiveDelayElapsedNanos, untrackedRequests,
            driverRunning ? elapsed(driverStartedNanos, now) : 0, driverMaxLagNanos);
    }
    private Sample sample(Active request, long now, long currentCpu) {
        long cpuNanos = request.phaseStartedCpuNanos < 0 || currentCpu < 0 ? -1 : elapsed(request.phaseStartedCpuNanos, currentCpu);
        return new Sample(request.requestId, request.threadId, request.scenario, request.phase, request.requestedNanos,
            elapsed(request.phaseStartedNanos, now), cpuNanos, elapsed(request.startedNanos, now));
    }
    private void remember(Sample sample) {
        recent[nextSample] = sample; nextSample = (nextSample + 1) % recent.length;
        sampleCount = Math.min(sampleCount + 1, recent.length);
        if (delay(sample.phase())) {
            delaySamples++;
            maxDelayElapsedNanos = Math.max(maxDelayElapsedNanos, sample.elapsedNanos());
            maxDelayOvershootNanos = Math.max(maxDelayOvershootNanos, sample.elapsedNanos() - sample.requestedNanos());
        }
    }
    private static boolean delay(Phase phase) { return phase == Phase.DELAY_HEADERS || phase == Phase.DELAY_BODY; }
    private static long elapsed(long started, long now) { return Math.max(0, now - started); }
    private static double millis(long nanos) { return nanos / 1_000_000.0; }
    @Override public synchronized void close() {
        closed = true; Arrays.fill(active, null); Arrays.fill(recent, null); sampleCount = nextSample = 0;
    }
    private static final class Active {
        final long requestId, threadId, startedNanos;
        final Scenario scenario;
        Phase phase = Phase.READ_BODY;
        long requestedNanos, phaseStartedNanos, phaseStartedCpuNanos;
        Active(long requestId, long threadId, Scenario scenario, long now, long currentCpu) {
            this.requestId = requestId; this.threadId = threadId; this.scenario = scenario;
            startedNanos = phaseStartedNanos = now; phaseStartedCpuNanos = currentCpu;
        }
    }
}
