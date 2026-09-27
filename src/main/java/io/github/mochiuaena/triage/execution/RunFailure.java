package io.github.mochiuaena.triage.execution;

/** Only application-owned messages may be included in persisted failures. */
public final class RunFailure extends RuntimeException {
    private final String code;

    public RunFailure(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() { return code; }
}
