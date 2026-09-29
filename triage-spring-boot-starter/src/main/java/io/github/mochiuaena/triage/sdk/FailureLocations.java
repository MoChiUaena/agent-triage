package io.github.mochiuaena.triage.sdk;

import java.util.*;

/** Only explicitly selected application packages; never stores a Throwable or its message. */
final class FailureLocations {
    record Frame(String className, String methodName, String fileName, Integer lineNumber,
                 @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) String sourceHash) {
        Frame(String className, String methodName, String fileName, Integer lineNumber) { this(className, methodName, fileName, lineNumber, null); }
    }
    record Location(String kind, List<String> exceptionTypes, List<Frame> frames, boolean truncated) {}
    private FailureLocations() {}
    static Location capture(Throwable error, TriageObservationProperties properties, String kind) {
        if (!properties.isExceptionLocations()) return null;
        var causes = new ArrayList<Throwable>();
        var types = new ArrayList<String>();
        var seen = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        Throwable cause = error;
        while (cause != null && seen.add(cause) && causes.size() < 4) {
            if (!javaName(cause.getClass().getName(), 240, true)) return null;
            causes.add(cause); types.add(cause.getClass().getName()); cause = cause.getCause();
        }
        boolean truncated = cause != null;
        var frames = new LinkedHashSet<Frame>();
        var versions = new HashMap<StackTraceElement, String>();
        if (properties.isSourceVersionChecks() && "HTTP_CLIENT_FAILURE".equals(kind)) {
            try {
                StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE).walk(stream -> {
                    stream.limit(128).filter(frame -> properties.getApplicationPackages().stream().anyMatch(prefix -> frame.getClassName().startsWith(prefix + ".")))
                        .limit(8).forEach(frame -> { String hash = SourceBuildVersions.sourceHash(frame.getDeclaringClass()); if (hash != null) versions.put(frame.toStackTraceElement(), hash); });
                    return null;
                });
            } catch (RuntimeException | LinkageError ignored) { /* Missing version evidence must not change the business exception. */ }
        }
        List<StackTraceElement[]> stacks = "HTTP_CLIENT_FAILURE".equals(kind)
            ? Collections.singletonList(Thread.currentThread().getStackTrace()) : causes.stream().map(Throwable::getStackTrace).toList();
        for (var stack : stacks) {
            if (stack.length > 128) truncated = true;
            for (int i = 0; i < Math.min(128, stack.length); i++) {
                var frame = stack[i];
                if (properties.getApplicationPackages().stream().noneMatch(prefix -> frame.getClassName().startsWith(prefix + "."))
                    || !javaName(frame.getClassName(), 240, true) || !method(frame.getMethodName())) continue;
                String file = frame.getFileName();
                if (file != null && !file.matches("[\\p{L}\\p{N}_$-]{1,150}\\.java")) file = null;
                var value = new Frame(frame.getClassName(), frame.getMethodName(), file,
                    frame.getLineNumber() > 0 && frame.getLineNumber() <= 1_000_000 ? frame.getLineNumber() : null, versions.get(frame));
                if (frames.size() == 8 && !frames.contains(value)) { truncated = true; break; }
                frames.add(value);
            }
        }
        return new Location(kind, List.copyOf(types), List.copyOf(frames), truncated);
    }
    static boolean javaName(String value, int max, boolean qualified) {
        if (value == null || value.isBlank() || value.length() > max) return false;
        for (String part : qualified ? value.split("\\.", -1) : new String[]{value})
            if (part.isEmpty() || !Character.isJavaIdentifierStart(part.codePointAt(0)) || !part.codePoints().allMatch(Character::isJavaIdentifierPart)) return false;
        return true;
    }
    private static boolean method(String value) { return "<init>".equals(value) || "<clinit>".equals(value) || javaName(value, 80, false); }
}
