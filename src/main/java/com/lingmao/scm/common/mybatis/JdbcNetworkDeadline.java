package com.lingmao.scm.common.mybatis;

/** 给探针和后台小批量任务用的短网络超时（客户端这边）。 */
public final class JdbcNetworkDeadline implements AutoCloseable {
    private static final ThreadLocal<Integer> CURRENT = new ThreadLocal<>();
    private final Integer previous;

    private JdbcNetworkDeadline(int millis) {
        if (millis < 1 || millis > 30_000) throw new IllegalArgumentException("Invalid JDBC network deadline");
        previous = CURRENT.get();
        CURRENT.set(previous == null ? millis : Math.min(previous, millis));
    }

    public static JdbcNetworkDeadline open(int millis) { return new JdbcNetworkDeadline(millis); }
    static Integer currentMillis() { return CURRENT.get(); }
    @Override public void close() { if (previous == null) CURRENT.remove(); else CURRENT.set(previous); }
}
