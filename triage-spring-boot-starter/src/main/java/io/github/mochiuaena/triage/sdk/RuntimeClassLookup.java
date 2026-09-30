package io.github.mochiuaena.triage.sdk;

/** The agent is loaded by the system loader; Spring Boot may load the Starter from a nested JAR. */
final class RuntimeClassLookup {
    static final class Budget {
        private final int limit;
        private long started = Long.MIN_VALUE;
        private int used;
        Budget(int limit) { this.limit = limit; }
        synchronized boolean take(long now) {
            if (started == Long.MIN_VALUE || now < started || now - started >= 1_000_000_000L) {
                started = now;
                used = 0;
            }
            if (used >= limit) return false;
            used++;
            return true;
        }
    }
    record Result(boolean active, Class<?>[] classes) {}
    private static final Budget BUDGET = new Budget(20);
    private RuntimeClassLookup() {}

    static Result find(String[] names) {
        var unknown = new Class<?>[names.length];
        try {
            Class<?> agent = Class.forName(RuntimeClassAgent.class.getName(), false, ClassLoader.getSystemClassLoader());
            if (!Boolean.TRUE.equals(agent.getMethod("active").invoke(null))) return new Result(false, unknown);
            if (!BUDGET.take(System.nanoTime())) return new Result(true, unknown);
            Object value = agent.getMethod("uniqueLoadedClasses", String[].class).invoke(null, (Object) names);
            if (value instanceof Class<?>[] classes && classes.length == names.length) return new Result(true, classes);
        } catch (ClassNotFoundException ignored) {
            return new Result(false, unknown);
        } catch (ReflectiveOperationException | SecurityException | LinkageError ignored) {
            // Without a usable premain bridge, no stack frame gains a version claim.
        }
        return new Result(true, unknown);
    }
}
