package com.lingmao.scm.common.mybatis;

import com.lingmao.scm.common.log.AuditContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.datasource.DelegatingDataSource;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;

/** Observes real JDBC execution without parameter values; audit identity is reset on every pool borrow. */
public final class ObservedDataSource extends DelegatingDataSource implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(ObservedDataSource.class);
    private final long slowMillis;
    public ObservedDataSource(DataSource delegate, long slowMillis) { super(delegate); this.slowMillis = slowMillis; }

    @Override public Connection getConnection() throws SQLException { return observe(super.getConnection()); }
    @Override public Connection getConnection(String user, String password) throws SQLException { return observe(super.getConnection(user, password)); }

    private Connection observe(Connection connection) throws SQLException {
        try {
            Integer networkMillis = JdbcNetworkDeadline.currentMillis();
            // Hikari tracks this setter and restores the pool's original value on close.
            // Apply it before the audit-context SQL, so even the first round trip is bounded.
            if (networkMillis != null) connection.setNetworkTimeout(Runnable::run, networkMillis);
            AuditContext context = AuditContext.current();
            // Session settings are overwritten even for anonymous/system work. No pooled identity can leak.
            try (PreparedStatement s = connection.prepareStatement("SELECT set_config('scm.audit_actor',?,false),"
                    + "set_config('scm.audit_ip',?,false),set_config('scm.audit_request',?,false),set_config('scm.audit_operation',?,false),"
                    + "set_config('scm.audit_replacements',?,false)")) {
                s.setQueryTimeout(2); s.setString(1, context.actor()); s.setString(2, context.ip());
                s.setString(3, context.requestId()); s.setString(4, context.operation());
                s.setString(5, AuditContext.replacements()); s.execute();
            }
        } catch (SQLException failure) { connection.close(); throw failure; }
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (p, method, args) -> {
            Object result = invoke(connection, method, args);
            if (result instanceof Statement statement && (method.getName().equals("prepareStatement")
                    || method.getName().equals("prepareCall") || method.getName().equals("createStatement"))) {
                String sql = args != null && args.length > 0 && args[0] instanceof String text ? text : "";
                return observedStatement(statement, sql);
            }
            return result;
        });
    }

    private Statement observedStatement(Statement statement, String preparedSql) {
        Class<?> api = statement instanceof java.sql.CallableStatement ? java.sql.CallableStatement.class
                : statement instanceof PreparedStatement ? PreparedStatement.class : Statement.class;
        return (Statement) Proxy.newProxyInstance(Statement.class.getClassLoader(), new Class<?>[]{api}, (p, method, args) -> {
            if (!method.getName().startsWith("execute")) return invoke(statement, method, args);
            String sql = !preparedSql.isEmpty() ? preparedSql
                    : args != null && args.length > 0 && args[0] instanceof String text ? text : "BATCH";
            long start = System.nanoTime(); boolean success = false;
            try { Object value = invoke(statement, method, args); success = true; return value; }
            finally {
                long millis = (System.nanoTime() - start) / 1_000_000;
                if (millis >= slowMillis) log.warn("slow_sql operation={} fingerprint={} elapsedMs={} success={}",
                        operation(sql), Integer.toUnsignedString(sql.hashCode(), 16), millis, success);
            }
        });
    }

    private static String operation(String sql) {
        String value = sql.stripLeading(); int end = value.indexOf(' ');
        String first = value.substring(0, Math.min(end < 0 ? value.length() : end, 12)).toUpperCase(Locale.ROOT);
        return switch (first) { case "SELECT", "INSERT", "UPDATE", "DELETE", "WITH", "BATCH" -> first; default -> "SQL"; };
    }
    private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
        try { return method.invoke(target, args); } catch (InvocationTargetException error) { throw error.getCause(); }
    }
    @Override public void close() throws Exception {
        if (getTargetDataSource() instanceof AutoCloseable closeable) closeable.close();
    }
}
