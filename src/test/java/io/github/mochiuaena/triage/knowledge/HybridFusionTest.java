package io.github.mochiuaena.triage.knowledge;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class HybridFusionTest {
    private static final UUID A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID B = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final UUID C = UUID.fromString("00000000-0000-0000-0000-00000000000c");

    @Test void cosineHandlesKnownDirectionsAndMismatchedVectors() {
        assertThat(HybridFusion.cosine(new float[] {1, 0}, new float[] {1, 0})).isEqualTo(1.0, within(1e-9));
        assertThat(HybridFusion.cosine(new float[] {1, 0}, new float[] {0, 1})).isEqualTo(0.0, within(1e-9));
        assertThat(HybridFusion.cosine(new float[] {1, 0}, new float[] {-2, 0})).isEqualTo(-1.0, within(1e-9));
        assertThat(HybridFusion.cosine(new float[] {0, 0}, new float[] {1, 1})).isEqualTo(0.0);
        assertThat(HybridFusion.cosine(new float[] {1}, new float[] {1, 1})).isEqualTo(0.0);
        assertThat(HybridFusion.cosine(null, new float[] {1})).isEqualTo(0.0);
    }

    @Test void reciprocalRankFusionSumsSharedCandidatesAndKeepsDeterministicTies() {
        var fused = HybridFusion.fuse(List.of(A, B), List.of(B, C), 60);
        // B appears on both lists: 1/(60+2) + 1/(60+1); A and C only once each.
        double shared = 1.0 / 62 + 1.0 / 61, onlyA = 1.0 / 61, onlyC = 1.0 / 62;
        assertThat(fused).extracting(HybridFusion.Fused::chunkId).containsExactly(B, A, C);
        assertThat(fused.get(0).score()).isEqualTo(shared, within(1e-12));
        assertThat(fused.get(0).keywordHit()).isTrue();
        assertThat(fused.get(0).vectorHit()).isTrue();
        assertThat(fused.get(1).score()).isEqualTo(onlyA, within(1e-12));
        assertThat(fused.get(1).keywordHit()).isTrue();
        assertThat(fused.get(1).vectorHit()).isFalse();
        assertThat(fused.get(2).score()).isEqualTo(onlyC, within(1e-12));
        // Equal fused scores keep the keyword order before the vector order.
        var tied = HybridFusion.fuse(List.of(A, B), List.of(B, A), 60);
        assertThat(tied).extracting(HybridFusion.Fused::chunkId).containsExactly(A, B);
        assertThat(HybridFusion.fuse(List.of(), List.of(), 60)).isEmpty();
    }

    @Test void rerankMergePutsScoredCandidatesFirstAndKeepsUnscoredFusedOrder() {
        List<Integer> fused = List.of(0, 1, 2, 3);
        // Service scored only documents 2 and 0, in that order of relevance.
        var ranked = HybridFusion.reranked(fused, List.of(2, 0), List.of(0.9, 0.4), i -> i);
        assertThat(ranked).containsExactly(2, 0, 1, 3);
        // Full coverage sorts purely by score.
        assertThat(HybridFusion.reranked(fused, List.of(1, 3, 0, 2), List.of(0.1, 0.4, 0.9, 0.2), i -> i))
            .containsExactly(0, 3, 2, 1);
        // No usable result keeps the fused order.
        assertThat(HybridFusion.reranked(fused, List.of(), List.of(), i -> i)).containsExactly(0, 1, 2, 3);
    }
}
