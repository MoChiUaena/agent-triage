package io.github.mochiuaena.triage.tools;

/** Safe, application-owned observation failures; never includes remote bodies or addresses. */
public final class ObservationFailure extends IllegalStateException {
    private final String code;
    public ObservationFailure(String code, String message) { super(message); this.code = code; }
    public String code() { return code; }
}
