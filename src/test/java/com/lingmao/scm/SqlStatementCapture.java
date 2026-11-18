package com.lingmao.scm;

import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Captures real prepared statements across the test application's MyBatis configurations.
 * Pagination's cached COUNT MappedStatement can retain an earlier configuration, so every
 * test context installs this interceptor and shares only the current thread's capture scope.
 */
@Intercepts(@Signature(type = StatementHandler.class, method = "prepare", args = {Connection.class, Integer.class}))
public final class SqlStatementCapture implements Interceptor {
    private static final ThreadLocal<List<BoundSql>> ACTIVE = new ThreadLocal<>();

    public static <T> Captured<T> capture(Supplier<T> query) {
        if (ACTIVE.get() != null) throw new IllegalStateException("SQL capture scopes must not overlap");
        List<BoundSql> statements = new ArrayList<>();
        ACTIVE.set(statements);
        try {
            return new Captured<>(query.get(), List.copyOf(statements));
        } finally {
            ACTIVE.remove();
        }
    }

    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        List<BoundSql> statements = ACTIVE.get();
        if (statements != null) statements.add(((StatementHandler) invocation.getTarget()).getBoundSql());
        return invocation.proceed();
    }

    public record Captured<T>(T result, List<BoundSql> statements) { }
}
