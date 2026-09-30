package io.github.mochiuaena.triage.sdk;

import com.zaxxer.hikari.HikariDataSource;
import java.io.PrintWriter;
import java.lang.reflect.*;
import java.sql.*;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Optional DataSource wrapper for JPA/JDBC operations during observed MVC requests. */
public final class TriageJpaObserver implements AutoCloseable {
    private final ObservationRecorder recorder;
    private TriageJdbcObserver sampler;

    TriageJpaObserver(TriageObservationProperties properties) {
        properties.validate();
        if (!properties.isJpaObservations()) throw new IllegalArgumentException("JPA observations are disabled");
        var database = new TriageObservationProperties();
        database.setKind(TriageObservationProperties.Kind.DATABASE);
        database.setServiceId(properties.getJpaServiceId());
        database.setDownstreamId(properties.getJpaDatabaseId());
        database.setMaxWindowMinutes(properties.getMaxWindowMinutes());
        database.setCapacity(properties.getCapacity());
        recorder = new ObservationRecorder(database);
    }

    /** The application supplies its Hikari pool; no business repository source needs to change. */
    public synchronized ObservedDataSource wrap(HikariDataSource pool) throws SQLException {
        Objects.requireNonNull(pool);
        if (sampler != null) throw new IllegalStateException("JPA observation pool is already attached");
        sampler = new TriageJdbcObserver(pool, recorder);
        return new ObservedDataSource(pool, this);
    }

    Object snapshot(int minutes, Instant end) {
        if (sampler == null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "JPA observation pool is not attached");
        return recorder.databaseSnapshot(minutes, end);
    }

    private Connection connection(Connector connector) throws SQLException {
        long start = System.nanoTime();
        TriageRequestFilter.Context request = TriageRequestFilter.CURRENT.get();
        try {
            Connection raw = connector.open();
            return proxy(raw, Connection.class, new ConnectionCalls(raw, this, request, ObservationRecorder.elapsed(start)));
        } catch (SQLException | RuntimeException e) {
            if (request != null) recorder.recordDatabase(ObservationRecorder.elapsed(start), ObservationRecorder.elapsed(start), 0,
                e instanceof SQLTransientConnectionException ? "DB_CONNECTION_ACQUIRE_TIMEOUT" : "DB_CONNECTION_ACQUIRE_FAILED", request.trace);
            throw e;
        }
    }

    private void statement(double acquisitionMs, double queryMs, String code, String trace) {
        recorder.recordDatabase(acquisitionMs + queryMs, acquisitionMs, queryMs, code, trace);
    }

    @Override public synchronized void close() { if (sampler != null) sampler.close(); }

    @FunctionalInterface private interface Connector { Connection open() throws SQLException; }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Object value, Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }

    private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
        try { return method.invoke(target, args); }
        catch (InvocationTargetException e) { throw e.getCause(); }
    }

    private static Object special(Object proxy, Object target, Method method, Object[] args) throws Throwable {
        if (method.getDeclaringClass() == Object.class) return switch (method.getName()) {
            case "equals" -> proxy == args[0];
            case "hashCode" -> System.identityHashCode(proxy);
            default -> "Observed JDBC handle";
        };
        if ("unwrap".equals(method.getName()) && args != null && args[0] instanceof Class<?> type && type.isInstance(proxy)) return proxy;
        if ("isWrapperFor".equals(method.getName()) && args != null && args[0] instanceof Class<?> type && type.isInstance(proxy)) return true;
        return invoke(target, method, args);
    }

    private record ConnectionCalls(Connection raw, TriageJpaObserver observer, TriageRequestFilter.Context acquired,
                                   double acquisitionMs, AtomicBoolean used) implements InvocationHandler {
        ConnectionCalls(Connection raw, TriageJpaObserver observer, TriageRequestFilter.Context acquired, double acquisitionMs) {
            this(raw, observer, acquired, acquisitionMs, new AtomicBoolean());
        }
        @Override public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (method.getDeclaringClass() == Object.class || "unwrap".equals(method.getName()) || "isWrapperFor".equals(method.getName()))
                return special(proxy, raw, method, args);
            Object value = TriageJpaObserver.invoke(raw, method, args);
            if (value instanceof Statement statement && (method.getName().startsWith("prepare") || "createStatement".equals(method.getName()))) {
                Class<? extends Statement> type = statement instanceof CallableStatement ? CallableStatement.class
                    : statement instanceof PreparedStatement ? PreparedStatement.class : Statement.class;
                return TriageJpaObserver.proxy(statement, type, new StatementCalls(statement, observer, acquired, acquisitionMs, used));
            }
            return value;
        }
    }

    private record StatementCalls(Statement raw, TriageJpaObserver observer, TriageRequestFilter.Context acquired,
                                  double acquisitionMs, AtomicBoolean used) implements InvocationHandler {
        @Override public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (method.getDeclaringClass() == Object.class || "unwrap".equals(method.getName()) || "isWrapperFor".equals(method.getName()))
                return special(proxy, raw, method, args);
            if (!("execute".equals(method.getName()) || "executeQuery".equals(method.getName())
                || "executeUpdate".equals(method.getName()) || "executeLargeUpdate".equals(method.getName())
                || "executeBatch".equals(method.getName()) || "executeLargeBatch".equals(method.getName())))
                return TriageJpaObserver.invoke(raw, method, args);
            TriageRequestFilter.Context request = TriageRequestFilter.CURRENT.get();
            if (request == null) return TriageJpaObserver.invoke(raw, method, args);
            double acquisition = request == acquired && used.compareAndSet(false, true) ? acquisitionMs : 0;
            long start = System.nanoTime();
            String code = null;
            try { return TriageJpaObserver.invoke(raw, method, args); }
            catch (SQLException | RuntimeException e) { code = "SQL_QUERY_FAILED"; throw e; }
            finally { observer.statement(acquisition, ObservationRecorder.elapsed(start), code, request.trace); }
        }
    }

    public static final class ObservedDataSource implements DataSource, AutoCloseable {
        private final HikariDataSource pool;
        private final TriageJpaObserver observer;
        private ObservedDataSource(HikariDataSource pool, TriageJpaObserver observer) { this.pool = pool; this.observer = observer; }
        @Override public Connection getConnection() throws SQLException { return observer.connection(pool::getConnection); }
        @Override public Connection getConnection(String user, String password) throws SQLException {
            return observer.connection(() -> pool.getConnection(user, password));
        }
        @Override public <T> T unwrap(Class<T> type) throws SQLException {
            if (type.isInstance(this)) return type.cast(this);
            return pool.unwrap(type);
        }
        @Override public boolean isWrapperFor(Class<?> type) throws SQLException { return type.isInstance(this) || pool.isWrapperFor(type); }
        @Override public PrintWriter getLogWriter() throws SQLException { return pool.getLogWriter(); }
        @Override public void setLogWriter(PrintWriter writer) throws SQLException { pool.setLogWriter(writer); }
        @Override public void setLoginTimeout(int seconds) throws SQLException { pool.setLoginTimeout(seconds); }
        @Override public int getLoginTimeout() throws SQLException { return pool.getLoginTimeout(); }
        @Override public Logger getParentLogger() throws SQLFeatureNotSupportedException { return pool.getParentLogger(); }
        @Override public void close() { pool.close(); }
    }
}
