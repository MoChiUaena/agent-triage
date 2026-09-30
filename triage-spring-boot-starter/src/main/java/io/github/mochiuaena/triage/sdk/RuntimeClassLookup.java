package io.github.mochiuaena.triage.sdk;

/** The agent is loaded by the system loader; Spring Boot may load the Starter from a nested JAR. */
final class RuntimeClassLookup {
    record Result(boolean active, Class<?>[] classes) {}
    private RuntimeClassLookup() {}

    static Result find(String[] names) {
        var unknown = new Class<?>[names.length];
        try {
            Class<?> agent = Class.forName(RuntimeClassAgent.class.getName(), false, ClassLoader.getSystemClassLoader());
            if (!Boolean.TRUE.equals(agent.getMethod("active").invoke(null))) return new Result(false, unknown);
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
