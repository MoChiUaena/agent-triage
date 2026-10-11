package io.github.mochiuaena.triage.sdk;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class HttpRequestReceiptTest {
    @Test void identifiesMissingDownstreamRequestByDriverIndexAndStartTime() {
        var receipts = new HttpRequestReceipt(4);
        receipts.started(0, 5_000, 1_000);
        receipts.started(1, 5_125, 1_125);
        receipts.started(2, 5_250, 1_250);
        assertThat(receipts.received(2)).isTrue();
        assertThat(receipts.received(0)).isTrue();
        assertThat(receipts.received(2)).isFalse();

        var snapshot = receipts.snapshot(3);
        assertThat(snapshot.missingCount()).isEqualTo(1);
        assertThat(snapshot.firstMissing()).containsExactly(new HttpRequestReceipt.Missing(1, 5_125, 1_125));
    }

    @Test void capsReportedMissingIndexesWithoutHidingTheTotal() {
        var receipts = new HttpRequestReceipt(12);
        for (int index = 0; index < 12; index++) receipts.started(index, 20_000 + index * 125, 10_000 + index * 125);
        receipts.received(0);

        var snapshot = receipts.snapshot(12);
        assertThat(snapshot.missingCount()).isEqualTo(11);
        assertThat(snapshot.firstMissing()).hasSize(8);
        assertThat(snapshot.firstMissing().getFirst().index()).isEqualTo(1);
        assertThat(snapshot.firstMissing().getLast().index()).isEqualTo(8);
    }

    @Test void progressCountsOnlyStartedAndArrivedRequests() {
        var receipts = new HttpRequestReceipt(4);
        receipts.started(0, 5_000, 1_000);
        receipts.started(2, 5_250, 1_250);
        receipts.received(2);
        assertThat(receipts.startedCount()).isEqualTo(2);
        assertThat(receipts.arrivedCount()).isEqualTo(1);
    }

    @Test void rejectsUnknownOrDuplicateRequestIndexes() {
        var receipts = new HttpRequestReceipt(2);
        assertThat(receipts.received(0)).isFalse();
        assertThat(receipts.received(2)).isFalse();
        assertThatThrownBy(() -> receipts.started(0, 5_000, 0)).isInstanceOf(IllegalArgumentException.class);
        receipts.started(0, 5_000, 1_000);
        assertThatThrownBy(() -> receipts.started(0, 5_001, 1_001)).isInstanceOf(IllegalArgumentException.class);
        assertThat(receipts.received(0)).isTrue();
        assertThat(receipts.received(0)).isFalse();
    }

    @Test void identifiesWhichArrivedRequestsStoppedBeforeBodyOrDelay() {
        var receipts = new HttpRequestReceipt(3);
        for (int index = 0; index < 3; index++) {
            receipts.started(index, 5_000 + index * 125, 1_000 + index * 125);
            receipts.received(index);
        }
        assertThat(receipts.delayEntered(1)).isFalse();
        receipts.bodyRead(0);
        receipts.delayEntered(0);
        receipts.bodyRead(1);

        var snapshot = receipts.snapshot(3);
        assertThat(snapshot.missingCount()).isZero();
        assertThat(snapshot.bodyReadCount()).isEqualTo(2);
        assertThat(snapshot.delayEnteredCount()).isEqualTo(1);
        assertThat(snapshot.firstStageGaps()).containsExactly(
            new HttpRequestReceipt.StageGap(1, true, false, 5_125, 1_125),
            new HttpRequestReceipt.StageGap(2, false, false, 5_250, 1_250));
    }

    @Test void snapshotsOneRequestWithoutCallingAnInFlightStageMissing() {
        var receipts = new HttpRequestReceipt(2);
        receipts.started(1, 5_125, 1_125);
        assertThat(receipts.stageAt(1)).isEqualTo(new HttpRequestReceipt.Stage(false, false, false, 5_125, 1_125));
        receipts.received(1);
        receipts.bodyRead(1);
        assertThat(receipts.stageAt(1)).isEqualTo(new HttpRequestReceipt.Stage(true, true, false, 5_125, 1_125));
        assertThat(receipts.stageAt(0)).isNull();
        assertThat(receipts.stageAt(2)).isNull();
    }

    @Test void accountsForIoAbortAfterBodyReadWithoutHidingOtherStageGaps() {
        var receipts = new HttpRequestReceipt(3);
        for (int index = 0; index < 3; index++) {
            receipts.started(index, 5_000 + index * 125, 1_000 + index * 125);
            receipts.received(index);
        }
        assertThat(receipts.ioAbortedBeforeDelay(0)).isFalse();
        receipts.bodyRead(0);
        assertThat(receipts.ioAbortedBeforeDelay(0)).isTrue();
        assertThat(receipts.ioAbortedBeforeDelay(0)).isFalse();
        assertThat(receipts.delayEntered(0)).isFalse();
        receipts.bodyRead(1);
        receipts.delayEntered(1);
        assertThat(receipts.ioAbortedBeforeDelay(1)).isFalse();

        var snapshot = receipts.snapshot(3);
        assertThat(snapshot.bodyReadCount()).isEqualTo(2);
        assertThat(snapshot.delayEnteredCount()).isEqualTo(1);
        assertThat(snapshot.earlyIoAbortCount()).isEqualTo(1);
        assertThat(snapshot.firstStageGaps()).containsExactly(
            new HttpRequestReceipt.StageGap(2, false, false, 5_250, 1_250));
    }
}
