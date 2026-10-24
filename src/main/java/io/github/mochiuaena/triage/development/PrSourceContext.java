package io.github.mochiuaena.triage.development;

import java.util.List;

/**
 * Optional restricted context from a registered local source index for a PR model review. Every
 * entry states its version-check outcome; only MATCHED entries carry excerpts and accept citations.
 */
public final class PrSourceContext {
    private PrSourceContext() {}

    public static final String MATCHED = "MATCHED", VERSION_MISMATCH = "VERSION_MISMATCH",
        NOT_INDEXED = "NOT_INDEXED", UNAVAILABLE = "UNAVAILABLE";

    /** state: MATCHED / VERSION_MISMATCH / NOT_INDEXED / UNAVAILABLE. */
    public record Entry(String file, String state, String message, List<SymbolView> symbols,
                        List<Caller> callers, List<String> implementations) {}

    public record SymbolView(String id, String path, String className, String method, String signature,
                             String route, int startLine, int endLine, String content) {}

    /** Static reverse-call candidate; state CANDIDATE, or AMBIGUOUS when overloads prevent resolution. */
    public record Caller(String path, String className, String method, int line, String call, String state) {}
}
