package io.github.mochiuaena.triage.development;

import java.util.regex.Pattern;

/** Handles credential forms common in source code in addition to log assignments. */
final class PrEvidenceRedactor {
    private static final String KEY = "(?:authorization|[A-Z0-9_-]{0,64}(?:TOKEN|SECRET|PASSWORD|API[-_]?KEY|PRIVATE[-_]?KEY)[A-Z0-9_-]{0,32})";
    private static final String QUOTED = "(?:\"(?:\\\\.|[^\"\\\\])*+(?:\"|$)|'(?:\\\\.|[^'\\\\])*+(?:'|$))";
    private static final Pattern ARGUMENT = Pattern.compile("(?i)([\"']" + KEY + "[\"']\\s*,\\s*)" + QUOTED);
    private static final Pattern AUTH_VALUE = Pattern.compile("(?i)\\b(Bearer|Basic)\\s+[^\\s\"',;)]+");
    private static final Pattern URL_PASSWORD = Pattern.compile("(?i)(https?://[^\\s/:@\"']{1,200}:)[^\\s/@\"']{1,500}(@)");
    private static final Pattern PRIVATE_KEY = Pattern.compile("(?s)-----BEGIN [A-Z ]*PRIVATE KEY-----.*?(?:-----END [A-Z ]*PRIVATE KEY-----|$)");
    private PrEvidenceRedactor() {}
    static String clean(String value) {
        String result = PRIVATE_KEY.matcher(value).replaceAll("[REDACTED PRIVATE KEY]");
        result = ARGUMENT.matcher(result).replaceAll("$1\"[REDACTED]\"");
        result = AUTH_VALUE.matcher(result).replaceAll("$1 [REDACTED]");
        result = URL_PASSWORD.matcher(result).replaceAll("$1[REDACTED]$2");
        return CiLogAnalyzer.redact(result);
    }
}
