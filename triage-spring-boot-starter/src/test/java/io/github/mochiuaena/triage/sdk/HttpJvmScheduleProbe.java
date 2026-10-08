package io.github.mochiuaena.triage.sdk;

import java.lang.management.ManagementFactory;
import java.util.*;

/** Bounded JVM-local wakeup witness for the optional HTTP resource diagnostic. */
final class HttpJvmScheduleProbe {
    private static final long INTERVAL_NANOS = 100_000_000L;
    private static final long THRESHOLD_NANOS = 50_000_000L;
    private static final int HISTORY_LIMIT = 512;
    private record Gap(long uptimeMs, long nanos) { }

    private final Deque<Gap> recent = new ArrayDeque<>();
    private Thread thread;
    private long previousNanos = -1, ticks, peakGapNanos;
    private boolean closed;

    static HttpJvmScheduleProbe start() {
        var probe = new HttpJvmScheduleProbe();
        probe.tick(System.nanoTime(), ManagementFactory.getRuntimeMXBean().getUptime());
        probe.thread = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try { Thread.sleep(100); }
                catch (InterruptedException stopped) { Thread.currentThread().interrupt(); break; }
                probe.tick(System.nanoTime(), ManagementFactory.getRuntimeMXBean().getUptime());
            }
        }, "resource-jvm-schedule");
        probe.thread.setDaemon(true);
        probe.thread.start();
        return probe;
    }

    synchronized void tick(long nowNanos, long uptimeMs) {
        if (closed) return;
        if (nowNanos < 0 || uptimeMs < 0) throw new IllegalArgumentException("Invalid JVM schedule time");
        long previous = previousNanos;
        previousNanos = nowNanos;
        if (previous < 0) return;
        long gap = Math.max(0, nowNanos - previous - INTERVAL_NANOS);
        ticks++;
        peakGapNanos = Math.max(peakGapNanos, gap);
        if (gap >= THRESHOLD_NANOS) {
            if (recent.size() == HISTORY_LIMIT) recent.removeFirst();
            recent.addLast(new Gap(uptimeMs, gap));
        }
    }

    synchronized long tickCount() { return ticks; }

    synchronized String render(long failureUptimeMs) {
        if (ticks == 0) return "RESOURCE_JVM_SCHEDULE kind=unavailable reason=no_ticks";
        var near = recent.stream().filter(value -> value.uptimeMs >= failureUptimeMs - 5_000
            && value.uptimeMs <= failureUptimeMs + 500).toList();
        long nearPeak = near.stream().mapToLong(Gap::nanos).max().orElse(-1);
        var lines = new ArrayList<String>();
        lines.add(String.format(Locale.ROOT,
            "RESOURCE_JVM_SCHEDULE kind=summary ticks=%d peakGapMs=%.3f recordedGaps=%d failureUptimeMs=%d nearEvents=%d nearPeakGapMs=%s intervalMs=100 thresholdMs=50 windowBeforeMs=5000 windowAfterMs=500",
            ticks, peakGapNanos / 1_000_000.0, recent.size(), failureUptimeMs, near.size(),
            nearPeak < 0 ? "unreported" : String.format(Locale.ROOT, "%.3f", nearPeak / 1_000_000.0)));
        near.stream().sorted(Comparator.comparingLong(Gap::nanos).reversed()).limit(8)
            .sorted(Comparator.comparingLong(Gap::uptimeMs))
            .forEach(value -> lines.add(String.format(Locale.ROOT,
                "RESOURCE_JVM_SCHEDULE kind=near uptimeMs=%d gapMs=%.3f", value.uptimeMs, value.nanos / 1_000_000.0)));
        return String.join("\n", lines);
    }

    boolean stop() {
        synchronized (this) { closed = true; recent.clear(); }
        if (thread == null) return true;
        thread.interrupt();
        try { thread.join(5_000); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return false; }
        return !thread.isAlive();
    }

    boolean stopped() { return thread == null || !thread.isAlive(); }
}
