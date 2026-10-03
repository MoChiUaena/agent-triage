package io.github.mochiuaena.triage.store;

import io.github.mochiuaena.triage.domain.TriageModel.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class HistoryGrowthTest {
    @TempDir Path directory;

    @Test void rejectsDatabaseAddressesOutsideTheDedicatedLoopbackFixture() {
        for (String url : List.of("jdbc:h2:file:./data/triage", "jdbc:postgresql://localhost:5432/triage",
                "jdbc:postgresql://remote.example:5432/history_growth_ci",
                "jdbc:postgresql://localhost:5432/history_growth_ci?currentSchema=public",
                "jdbc:postgresql://localhost:5432/history_growth_ci;other",
                "jdbc:postgresql://localhost:5432/history_growth_ci/extra",
                "jdbc:postgresql://localhost:0/history_growth_ci", "jdbc:postgresql://localhost:65536/history_growth_ci")) {
            assertThatThrownBy(() -> HistoryGrowthFixture.validatePostgresUrl(url)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> HistoryGrowthFixture.validatePostgresUrl(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatCode(() -> HistoryGrowthFixture.validatePostgresUrl("jdbc:postgresql://127.0.0.1:5432/history_growth_ci"))
            .doesNotThrowAnyException();
        assertThatCode(() -> HistoryGrowthFixture.validatePostgresUrl("jdbc:postgresql://localhost:15432/history_growth_ci"))
            .doesNotThrowAnyException();
    }

    @Test void rejectsUnboundedOrNonIncreasingCheckpointRequests() {
        for (String value : List.of("1,2", "1,2,3", "0,120,240", "120,120,240", "240,120,360", "120,240,10001",
                "120,x,360", "24,48,72,96,120,144")) {
            assertThatThrownBy(() -> HistoryGrowthFixture.checkpoints(value)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(HistoryGrowthFixture.checkpoints(null)).containsExactly(120, 240, 360);
        assertThat(HistoryGrowthFixture.checkpoints("1000,5000,10000")).containsExactly(1000, 5000, 10000);
    }

    @Test @Timeout(value = 5, unit = TimeUnit.MINUTES)
    void fileH2HistoryGrowsCleansUpAndRecoversFromAFreshDataSource() throws Exception {
        try (var fixture = HistoryGrowthFixture.h2(directory)) { validateGrowth(fixture, "h2"); }
    }

    @Test @Timeout(value = 5, unit = TimeUnit.MINUTES)
    void dedicatedPostgresHistoryGrowsCleansUpAndRecoversFromAFreshDataSource() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("history.growth.postgres"), "Optional dedicated PostgreSQL fixture");
        try (var fixture = HistoryGrowthFixture.postgres(System.getenv("HISTORY_GROWTH_DB_URL"),
                System.getenv("HISTORY_GROWTH_DB_USER"), System.getenv("HISTORY_GROWTH_DB_PASSWORD"))) {
            validateGrowth(fixture, "postgres");
        }
    }

    @Test void smallHistoriesDoNotReportFirstPageQueriesAsCursorSamples() throws Exception {
        var measurements = new ArrayList<HistoryGrowthFixture.Measurement>();
        try (var fixture = HistoryGrowthFixture.h2(directory)) {
            int loaded = 0;
            for (int checkpoint : HistoryGrowthFixture.checkpoints("24,48,72")) {
                fixture.append(loaded, checkpoint); loaded = checkpoint;
                verifyQueries(fixture, loaded, false); verifyCursorTraversal(fixture, loaded, false);
                measurements.add(fixture.measure(checkpoint, 0, 0, 0));
            }
            long start = System.nanoTime();
            long deleted = fixture.cleanup(24);
            long cleanupNanos = System.nanoTime() - start;
            assertThat(deleted).isEqualTo(24);
            verifyQueries(fixture, 72, true); verifyCursorTraversal(fixture, 72, true);
            measurements.add(fixture.measure(72, 1, 24, cleanupNanos));
            fixture.reopen();
            verifyQueries(fixture, 72, true); verifyCursorTraversal(fixture, 72, true);
            measurements.add(fixture.measure(72, 2, 24, cleanupNanos));
        }
        assertThat(measurements.get(3).cleanupNanos()).isPositive();
        assertThat(measurements.get(4).cleanupNanos()).isEqualTo(measurements.get(3).cleanupNanos());
        for (int i : List.of(0, 1, 3, 4)) {
            assertThat(measurements.get(i).cursorPage().minimumNanos()).isZero();
            assertThat(measurements.get(i).cursorPage().medianNanos()).isZero();
            assertThat(measurements.get(i).cursorPage().maximumNanos()).isZero();
        }
        assertThat(measurements.get(2).cursorPage().medianNanos()).isPositive();
        HistoryGrowthFixture.report("h2-small", measurements);
        List<String> lines = Files.readAllLines(Path.of("target", "history-growth", "h2-small.csv"));
        List<String> header = List.of(lines.getFirst().split(","));
        int samplesColumn = header.indexOf("cursor_page_samples");
        assertThat(samplesColumn).isGreaterThanOrEqualTo(0);
        int[] expectedSamples = {0, 0, 5, 0, 0};
        for (int i = 0; i < expectedSamples.length; i++) {
            assertThat(Integer.parseInt(lines.get(i + 1).split(",")[samplesColumn])).isEqualTo(expectedSamples[i]);
        }
    }

    private void validateGrowth(HistoryGrowthFixture fixture, String name) throws Exception {
        int[] checkpoints = HistoryGrowthFixture.checkpoints(System.getProperty("history.growth.checkpoints"));
        var measurements = new ArrayList<HistoryGrowthFixture.Measurement>();
        int loaded = 0;
        for (int checkpoint : checkpoints) {
            fixture.append(loaded, checkpoint); loaded = checkpoint;
            verifyQueries(fixture, loaded, false);
            var measured = fixture.measure(checkpoint, 0, 0, 0);
            assertThat(measured.rows()).isEqualTo(checkpoint);
            assertThat(measured.projectedRows()).isEqualTo(checkpoint);
            assertThat(measured.minPayloadBytes()).isGreaterThan(1000);
            assertThat(measured.maxPayloadBytes()).isGreaterThan(measured.minPayloadBytes() * 3);
            assertThat(measured.physicalBytes()).isPositive();
            assertThat(measured.cursorPage().samples()).isEqualTo(checkpoint > 50 ? 5 : 0);
            if (!measurements.isEmpty()) {
                assertThat(measured.payloadBytes()).isGreaterThan(measurements.getLast().payloadBytes());
                // Allocation can remain constant between checkpoints; report it without a monotonicity gate.
            }
            measurements.add(measured);
        }
        verifyCursorTraversal(fixture, loaded, false);
        long eligible = slots(loaded, 6, 7, 8, 9);
        assertThat(fixture.history.retentionPreview(HistoryGrowthFixture.CUTOFF).eligibleCount()).isEqualTo(eligible);
        assertThatThrownBy(() -> fixture.cleanup(eligible + 1)).isInstanceOf(HistoryRepository.RetentionChanged.class);
        assertThat(fixture.page(HistoryGrowthFixture.ALL, null).total()).isEqualTo(loaded);
        long start = System.nanoTime();
        long deleted = fixture.cleanup(eligible);
        long cleanupNanos = System.nanoTime() - start;
        assertThat(deleted).isEqualTo(eligible);
        verifyQueries(fixture, loaded, true);
        verifyCursorTraversal(fixture, loaded, true);
        for (int i : List.of(0, 1, 2, 10, 11)) {
            var saved = fixture.runs.find(HistoryGrowthFixture.id(i)).orElseThrow();
            assertThat(saved.synthetic()).isTrue();
            assertThat(saved.events()).isNotEmpty(); assertThat(saved.evidence()).isNotEmpty();
            assertThat(saved.question()).isEqualTo("Synthetic triage request " + i);
        }
        for (int i : List.of(6, 7, 8, 9)) assertThat(fixture.runs.find(HistoryGrowthFixture.id(i))).isEmpty();
        var cleaned = fixture.measure(loaded, 1, eligible, cleanupNanos); measurements.add(cleaned);
        assertThat(cleaned.rows()).isEqualTo(loaded - eligible);
        assertThat(cleaned.payloadBytes()).isLessThan(measurements.get(measurements.size() - 2).payloadBytes());
        var retained = List.of(0, 1, 2, 3, 4, 5, 10, 11).stream()
            .map(i -> fixture.runs.find(HistoryGrowthFixture.id(i)).orElseThrow()).toList();
        RunRepository oldRuns = fixture.runs;
        HistoryRepository oldHistory = fixture.history;
        fixture.reopen();
        assertThat(fixture.runs).isNotSameAs(oldRuns); assertThat(fixture.history).isNotSameAs(oldHistory);
        for (var saved : retained) assertThat(fixture.runs.find(saved.id())).contains(saved);
        assertThat(fixture.runs.unfinished()).hasSize((int) slots(loaded, 0, 1));
        verifyQueries(fixture, loaded, true); verifyCursorTraversal(fixture, loaded, true);
        var reopened = fixture.measure(loaded, 2, eligible, cleanupNanos); measurements.add(reopened);
        assertThat(reopened.rows()).isEqualTo(cleaned.rows());
        assertThat(reopened.projectedRows()).isEqualTo(cleaned.rows());
        assertThat(reopened.payloadBytes()).isEqualTo(cleaned.payloadBytes());
        HistoryGrowthFixture.report(name, measurements);
        List<String> lines = Files.readAllLines(Path.of("target", "history-growth", name + ".csv"));
        assertThat(lines).hasSize(checkpoints.length + 3);
        for (String line : lines.subList(1, lines.size())) {
            assertThat(line).matches("[0-9]+(,[0-9]+){27}");
        }
    }

    private void verifyQueries(HistoryGrowthFixture fixture, int loaded, boolean cleaned) {
        long expected = cleaned ? loaded - slots(loaded, 6, 7, 8, 9) : loaded;
        var page = fixture.page(HistoryGrowthFixture.ALL, null);
        assertThat(page.total()).isEqualTo(expected);
        assertThat(page.items()).hasSize((int) Math.min(50, expected));
        if (expected > 50) {
            assertThat(page.nextCursor()).isNotNull();
            var second = fixture.page(HistoryGrowthFixture.ALL, page.nextCursor());
            assertThat(second.items()).extracting(HistoryRepository.Entry::id)
                .doesNotContainAnyElementsOf(page.items().stream().map(HistoryRepository.Entry::id).toList());
        }
        long selected = cleaned ? 0 : slots(loaded, 6);
        var filtered = fixture.page(HistoryGrowthFixture.SELECTED, null);
        assertThat(filtered.total()).isEqualTo(selected);
        for (var item : filtered.items()) {
            assertThat(item.service()).isEqualTo("synthetic-orders");
            assertThat(item.mode()).isEqualTo("MODEL"); assertThat(item.status()).isEqualTo(Status.SUCCEEDED);
            assertThat(item.endpoint().id()).isEqualTo(HistoryGrowthFixture.ENDPOINT_ID);
            assertThat(item.durationMillis()).isEqualTo(1100);
        }
        assertThat(fixture.statistics(HistoryGrowthFixture.SELECTED).total()).isEqualTo(selected);
        var statistics = fixture.statistics(HistoryGrowthFixture.ALL);
        assertThat(statistics.total()).isEqualTo(expected);
        assertThat(statistics.statuses()).containsAllEntriesOf(Map.of(
            Status.QUEUED, slots(loaded, 0), Status.RUNNING, slots(loaded, 1),
            Status.SUCCEEDED, slots(loaded, cleaned ? new int[]{2, 10} : new int[]{2, 6, 10}),
            Status.INSUFFICIENT_EVIDENCE, slots(loaded, cleaned ? new int[]{3} : new int[]{3, 7}),
            Status.FAILED, slots(loaded, cleaned ? new int[]{4, 11} : new int[]{4, 8, 11}),
            Status.CANCELLED, slots(loaded, cleaned ? new int[]{5} : new int[]{5, 9})));
        assertThat(statistics.toolCalls()).isEqualTo(expected * 6);
        long called = slots(loaded, cleaned ? new int[]{0, 3} : new int[]{0, 3, 6, 9});
        assertThat(statistics.modelCalls()).isEqualTo(called * 2);
        assertThat(statistics.usage().calledRuns()).isEqualTo(called);
        assertThat(statistics.usage().completeRuns()).isEqualTo(slots(loaded, 3));
        assertThat(statistics.usage().partialRuns()).isEqualTo(cleaned ? 0 : slots(loaded, 6));
        assertThat(statistics.usage().missingRuns()).isEqualTo(slots(loaded, cleaned ? new int[]{0} : new int[]{0, 9}));
        long complete = slots(loaded, 3), partial = cleaned ? 0 : slots(loaded, 6);
        assertThat(statistics.usage().knownUsage()).isEqualTo(new TokenUsage(complete * 1200 + partial * 1000,
            complete * 180 + partial * 120, complete * 1380 + partial * 1120));
        assertThat(statistics.durations().samples()).isEqualTo(expected - slots(loaded, 0, 1));
        assertThat(statistics.durations().maximumMillis()).isEqualTo(1600);
        assertThat(statistics.durations().p95Millis()).isEqualTo(1600);
    }

    private void verifyCursorTraversal(HistoryGrowthFixture fixture, int loaded, boolean cleaned) {
        Set<UUID> expected = new HashSet<>(), seen = new HashSet<>();
        for (int i = 0; i < loaded; i++) if (!cleaned || i % 12 < 6 || i % 12 > 9) expected.add(new UUID(0, i + 1L));
        String cursor = null;
        do {
            var page = fixture.page(HistoryGrowthFixture.ALL, cursor);
            for (var item : page.items()) assertThat(seen.add(item.id())).as("Each persisted row occurs once").isTrue();
            cursor = page.nextCursor();
        } while (cursor != null);
        assertThat(seen).containsExactlyInAnyOrderElementsOf(expected);
    }

    private long slots(int rows, int... slots) {
        long count = 0;
        for (int slot : slots) count += rows / 12 + (rows % 12 > slot ? 1 : 0);
        return count;
    }
}
