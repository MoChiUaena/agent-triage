package io.github.mochiuaena.triage.sdk;

import java.lang.instrument.Instrumentation;
import java.util.HashMap;

/** Optional premain entry point. It observes loaded class identities without transforming classes. */
public final class RuntimeClassAgent {
    private static volatile Instrumentation instrumentation;
    private RuntimeClassAgent() {}

    public static void premain(String options, Instrumentation runtime) {
        instrumentation = runtime;
    }

    public static boolean active() { return instrumentation != null; }

    public static Class<?>[] uniqueLoadedClasses(String[] names) {
        if (names == null || names.length > 8) return new Class<?>[0];
        var result = new Class<?>[names.length];
        var runtime = instrumentation;
        if (runtime == null || names.length == 0) return result;
        var indices = new HashMap<String, Integer>();
        for (int i = 0; i < names.length; i++) {
            if (names[i] == null || names[i].length() > 240 || indices.putIfAbsent(names[i], i) != null) return new Class<?>[0];
        }
        var ambiguous = new boolean[names.length];
        for (Class<?> loaded : runtime.getAllLoadedClasses()) {
            Integer index = indices.get(loaded.getName());
            if (index == null || ambiguous[index]) continue;
            if (result[index] == null) result[index] = loaded;
            else if (result[index] != loaded) { result[index] = null; ambiguous[index] = true; }
        }
        return result;
    }
}
