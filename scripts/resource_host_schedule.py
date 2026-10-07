"""Bounded out-of-JVM scheduler witness for the owned HTTP test sampler."""
from collections import deque
import re
import threading
import time

INTERVAL_MS = 100
SIGNIFICANT_GAP_MS = 50
RECENT_EVENTS = 512
NEAR_BEFORE_MS = 5000
NEAR_AFTER_MS = 500
REPORTED_NEAR_EVENTS = 8


def failure_wall_ms(workload_text):
    for line in workload_text.splitlines():
        if line.startswith('RESOURCE_UNEXPECTED_HTTP_FAILURE ') and len(line) <= 2048:
            match = re.search(r'\bwallClockMs=(\d+)(?:\s|$)', line)
            if match is not None:
                return int(match[1])
    return None


class HostSchedulingProbe:
    def __init__(self, clock=time.monotonic_ns, wall_clock_ms=None):
        self._clock = clock
        self._wall_clock_ms = wall_clock_ms or (lambda: time.time_ns() // 1_000_000)
        self._lock = threading.Lock()
        self._stop = threading.Event()
        self._thread = None
        self._last_ns = None
        self._ticks = 0
        self._peak_gap_ns = 0
        self._recent = deque(maxlen=RECENT_EVENTS)

    def tick(self, monotonic_ns, wall_ms):
        if type(monotonic_ns) is not int or type(wall_ms) is not int or monotonic_ns < 0 or wall_ms < 0:
            raise ValueError('Invalid sampler heartbeat time')
        with self._lock:
            previous = self._last_ns
            self._last_ns = monotonic_ns
            if previous is None:
                return
            gap_ns = max(0, monotonic_ns - previous - INTERVAL_MS * 1_000_000)
            self._ticks += 1
            self._peak_gap_ns = max(self._peak_gap_ns, gap_ns)
            if gap_ns >= SIGNIFICANT_GAP_MS * 1_000_000:
                self._recent.append((wall_ms, gap_ns))

    def start(self):
        if self._thread is not None:
            raise ValueError('Sampler heartbeat already started')
        self.tick(self._clock(), self._wall_clock_ms())
        self._thread = threading.Thread(target=self._run, name='triage-host-schedule', daemon=True)
        self._thread.start()

    def _run(self):
        while not self._stop.wait(INTERVAL_MS / 1000):
            self.tick(self._clock(), self._wall_clock_ms())

    def stop(self):
        if self._thread is None:
            return True
        self._stop.set()
        self._thread.join(timeout=1)
        return not self._thread.is_alive()

    def render(self, failure_wall):
        with self._lock:
            ticks, peak_ns, recent = self._ticks, self._peak_gap_ns, tuple(self._recent)
        if ticks == 0:
            return 'RESOURCE_HOST_SCHEDULE kind=unavailable reason=no_ticks'
        near = [item for item in recent if failure_wall is not None and
            failure_wall - NEAR_BEFORE_MS <= item[0] <= failure_wall + NEAR_AFTER_MS]
        near_peak = max((gap for _, gap in near), default=None)
        near_peak_text = f'{near_peak / 1_000_000:.3f}' if near_peak is not None else 'unreported'
        summary = (f'RESOURCE_HOST_SCHEDULE kind=summary ticks={ticks} peakGapMs={peak_ns / 1_000_000:.3f} '
            f'recordedGaps={len(recent)} failureWallMs={failure_wall if failure_wall is not None else "unreported"} '
            f'nearEvents={len(near) if failure_wall is not None else "unreported"} '
            f'nearPeakGapMs={near_peak_text} intervalMs={INTERVAL_MS} thresholdMs={SIGNIFICANT_GAP_MS} '
            f'windowBeforeMs={NEAR_BEFORE_MS} windowAfterMs={NEAR_AFTER_MS}')
        selected = sorted(sorted(near, key=lambda item: item[1], reverse=True)[:REPORTED_NEAR_EVENTS])
        lines = [summary]
        lines.extend(f'RESOURCE_HOST_SCHEDULE kind=near wallMs={wall} gapMs={gap / 1_000_000:.3f}'
            for wall, gap in selected)
        return '\n'.join(lines)
