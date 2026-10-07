"""Bounded numeric safepoint evidence from the owned HTTP test JVM."""
import heapq
import re

SAFEPOINT = re.compile(
    r'^\[(\d+)ms\]\[info\]\[safepoint\] Safepoint "[^"\r\n]{1,80}", '
    r'Time since last: \d+ ns, Reaching safepoint: (\d+) ns, Cleanup: (\d+) ns, '
    r'At safepoint: (\d+) ns, Total:? (\d+) ns$')
NEAR_BEFORE_MS = 5000
NEAR_AFTER_MS = 500
MAX_NEAR_EVENTS = 8


def parse_safepoint_line(line):
    if len(line) > 512:
        return None
    match = SAFEPOINT.fullmatch(line.rstrip('\r\n'))
    if match is None:
        return None
    uptime, reach, cleanup, at, total = (int(value) for value in match.groups())
    if total < max(reach, cleanup, at):
        return None
    return dict(uptimeMs=uptime, reachNs=reach, cleanupNs=cleanup, atNs=at, totalNs=total)


def failure_uptime_ms(workload_text):
    for line in workload_text.splitlines():
        if line.startswith('RESOURCE_UNEXPECTED_HTTP_FAILURE ') and len(line) <= 2048:
            match = re.search(r'\bjvmUptimeMs=(\d+)(?:\s|$)', line)
            if match is not None:
                return int(match[1])
    return None


def safepoint_diagnostics(lines, failure_uptime):
    count = near_count = max_total = near_max = 0
    largest_near = []
    for line in lines:
        event = parse_safepoint_line(line)
        if event is None:
            continue
        count += 1
        max_total = max(max_total, event['totalNs'])
        if failure_uptime is None or not failure_uptime - NEAR_BEFORE_MS <= event['uptimeMs'] <= failure_uptime + NEAR_AFTER_MS:
            continue
        near_count += 1
        near_max = max(near_max, event['totalNs'])
        item = (event['totalNs'], event['uptimeMs'], event['reachNs'], event['cleanupNs'], event['atNs'])
        if len(largest_near) < MAX_NEAR_EVENTS:
            heapq.heappush(largest_near, item)
        elif item > largest_near[0]:
            heapq.heapreplace(largest_near, item)
    if count == 0:
        return 'RESOURCE_SAFEPOINT kind=unavailable reason=no_numeric_events'
    uptime = str(failure_uptime) if failure_uptime is not None else 'unreported'
    near = str(near_count) if failure_uptime is not None else 'unreported'
    near_peak = f'{near_max / 1_000_000:.3f}' if near_count else 'unreported'
    output = [f'RESOURCE_SAFEPOINT kind=summary events={count} peakTotalMs={max_total / 1_000_000:.3f} '
        f'failureUptimeMs={uptime} nearEvents={near} nearPeakTotalMs={near_peak} '
        f'windowBeforeMs={NEAR_BEFORE_MS} windowAfterMs={NEAR_AFTER_MS}']
    for total, when, reach, cleanup, at in sorted(largest_near, key=lambda item: item[1]):
        output.append(f'RESOURCE_SAFEPOINT kind=near uptimeMs={when} reachMs={reach / 1_000_000:.3f} '
            f'cleanupMs={cleanup / 1_000_000:.3f} atMs={at / 1_000_000:.3f} totalMs={total / 1_000_000:.3f}')
    return '\n'.join(output)
