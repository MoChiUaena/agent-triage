package io.github.mochiuaena.triage.knowledge;

import java.util.*;
import java.util.function.Function;

/**
 * Deterministic fusion helpers for the optional hybrid search: Reciprocal Rank Fusion over the
 * keyword and vector candidate orders, cosine similarity and a rerank merge that keeps candidates
 * the rerank service did not score behind the scored ones in their fused order.
 */
public final class HybridFusion {
    private HybridFusion() {}

    public record Fused(UUID chunkId, double score, boolean keywordHit, boolean vectorHit) {}

    public static List<Fused> fuse(List<UUID> keywordRank, List<UUID> vectorRank, int k) {
        Map<UUID, Integer> keyword = positions(keywordRank), vector = positions(vectorRank);
        Set<UUID> all = new LinkedHashSet<>(keywordRank);
        all.addAll(vectorRank);
        record Part(UUID id, double score, int keywordAt, int vectorAt) {}
        List<Part> parts = new ArrayList<>();
        for (UUID id : all) {
            int kw = keyword.getOrDefault(id, -1), vec = vector.getOrDefault(id, -1);
            double score = (kw >= 0 ? 1.0 / (k + kw + 1) : 0) + (vec >= 0 ? 1.0 / (k + vec + 1) : 0);
            parts.add(new Part(id, score, kw, vec));
        }
        parts.sort(Comparator.comparingDouble(Part::score).reversed()
            .thenComparingInt(p -> p.keywordAt() < 0 ? Integer.MAX_VALUE : p.keywordAt())
            .thenComparingInt(p -> p.vectorAt() < 0 ? Integer.MAX_VALUE : p.vectorAt()));
        return parts.stream()
            .map(p -> new Fused(p.id(), p.score(), p.keywordAt() >= 0, p.vectorAt() >= 0))
            .toList();
    }

    private static Map<UUID, Integer> positions(List<UUID> rank) {
        Map<UUID, Integer> positions = new HashMap<>();
        for (int i = 0; i < rank.size(); i++) positions.putIfAbsent(rank.get(i), i);
        return positions;
    }

    public static double cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length || a.length == 0) return 0;
        double dot = 0, left = 0, right = 0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            left += (double) a[i] * a[i];
            right += (double) b[i] * b[i];
        }
        if (left == 0 || right == 0) return 0;
        return dot / (Math.sqrt(left) * Math.sqrt(right));
    }

    /** Ranks the reranked candidates by descending score first, then the unscored ones in fused order. */
    public static <T> List<T> reranked(List<T> fused, List<Integer> scoredIndexes, List<Double> scores,
                                       Function<T, Integer> indexOf) {
        Map<Integer, Double> byIndex = new HashMap<>();
        for (int i = 0; i < scoredIndexes.size(); i++) byIndex.put(scoredIndexes.get(i), scores.get(i));
        List<T> ranked = new ArrayList<>(fused.stream()
            .filter(c -> byIndex.containsKey(indexOf.apply(c)))
            .sorted(Comparator.comparingDouble((T c) -> byIndex.get(indexOf.apply(c))).reversed())
            .toList());
        fused.stream().filter(c -> !byIndex.containsKey(indexOf.apply(c))).forEach(ranked::add);
        return ranked;
    }
}
