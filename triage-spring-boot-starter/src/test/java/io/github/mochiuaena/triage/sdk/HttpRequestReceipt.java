package io.github.mochiuaena.triage.sdk;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

/** Numeric driver-to-downstream receipt for the owned HTTP workload only. */
final class HttpRequestReceipt {
    record Missing(int index, long startedUptimeMs, long startedWallMs) { }
    record StageGap(int index, boolean bodyRead, boolean delayEntered, long startedUptimeMs, long startedWallMs) { }
    record Snapshot(int missingCount, List<Missing> firstMissing, int bodyReadCount, int delayEnteredCount,
                    List<StageGap> firstStageGaps) { }

    private final long[] startedUptimeMs, startedWallMs;
    private final BitSet received, bodyRead, delayEntered;

    HttpRequestReceipt(int requests) {
        if (requests < 1 || requests > 7200 * HttpResourceLifecycleTest.RPS)
            throw new IllegalArgumentException("Invalid owned HTTP request count");
        startedUptimeMs = new long[requests];
        startedWallMs = new long[requests];
        received = new BitSet(requests);
        bodyRead = new BitSet(requests);
        delayEntered = new BitSet(requests);
    }

    synchronized void started(int index, long uptimeMs, long wallMs) {
        if (index < 0 || index >= startedWallMs.length || uptimeMs <= 0 || wallMs <= 0 || startedWallMs[index] != 0)
            throw new IllegalArgumentException("Invalid owned HTTP request start");
        startedUptimeMs[index] = uptimeMs;
        startedWallMs[index] = wallMs;
    }

    synchronized boolean received(int index) {
        if (index < 0 || index >= startedWallMs.length || startedWallMs[index] == 0 || received.get(index)) return false;
        received.set(index);
        return true;
    }

    synchronized boolean bodyRead(int index) {
        if (index < 0 || index >= startedWallMs.length || !received.get(index) || bodyRead.get(index)) return false;
        bodyRead.set(index);
        return true;
    }

    synchronized boolean delayEntered(int index) {
        if (index < 0 || index >= startedWallMs.length || !bodyRead.get(index) || delayEntered.get(index)) return false;
        delayEntered.set(index);
        return true;
    }

    synchronized int startedCount() {
        int count = 0;
        for (long wallMs : startedWallMs) if (wallMs != 0) count++;
        return count;
    }

    synchronized int arrivedCount() { return received.cardinality(); }

    synchronized Snapshot snapshot(int issued) {
        if (issued < 0 || issued > startedWallMs.length) throw new IllegalArgumentException("Invalid issued count");
        int missingCount = 0;
        var first = new ArrayList<Missing>(8);
        var stageGaps = new ArrayList<StageGap>(8);
        for (int index = 0; index < issued; index++) {
            if (!received.get(index)) {
                missingCount++;
                if (first.size() < 8) first.add(new Missing(index, startedUptimeMs[index], startedWallMs[index]));
            } else if ((!bodyRead.get(index) || !delayEntered.get(index)) && stageGaps.size() < 8) {
                stageGaps.add(new StageGap(index, bodyRead.get(index), delayEntered.get(index),
                    startedUptimeMs[index], startedWallMs[index]));
            }
        }
        return new Snapshot(missingCount, List.copyOf(first), bodyRead.get(0, issued).cardinality(),
            delayEntered.get(0, issued).cardinality(), List.copyOf(stageGaps));
    }
}
