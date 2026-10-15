package io.github.mochiuaena.triage.development;

import java.util.regex.*;

/** Redacts multiline values without shifting later evidence line numbers. */
final class IssueEvidenceText {
    private static final Pattern PRIVATE = Pattern.compile("(?s)-----BEGIN [A-Z ]*PRIVATE KEY-----.*?(?:-----END [A-Z ]*PRIVATE KEY-----|$)");
    private static final String KEY = "(?:authorization|[A-Z0-9_-]{0,64}(?:TOKEN|SECRET|PASSWORD|API[-_]?KEY|PRIVATE[-_]?KEY)[A-Z0-9_-]{0,32})";
    private static final String VALUE = "(?:\"(?:\\\\.|[^\"\\\\])*+(?:\"|$)|'(?:\\\\.|[^'\\\\])*+(?:'|$))";
    private static final Pattern QUOTED = Pattern.compile("(?i)(?:[\"']?" + KEY + "[\"']?\\s*[:=]\\s*|[\"']" + KEY + "[\"']\\s*,\\s*)" + VALUE);
    private IssueEvidenceText() {}
    static String clean(String raw) {
        String value = replace(PRIVATE, raw), masked = replace(QUOTED, value);
        return masked.lines().map(PrEvidenceRedactor::clean).collect(java.util.stream.Collectors.joining("\n"));
    }
    private static String replace(Pattern pattern, String input) {
        return pattern.matcher(input).replaceAll(match -> "[REDACTED]" + "\n".repeat((int) match.group().chars().filter(c -> c == '\n').count()));
    }
}
