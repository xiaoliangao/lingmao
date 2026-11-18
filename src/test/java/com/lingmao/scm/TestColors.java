package com.lingmao.scm;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

/** 真库测试共享三位颜色码空间：顺序取候选并由真实唯一索引原子认领，不依赖随机碰撞概率。 */
public final class TestColors {
    private static final String DIGITS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final int CAPACITY = 36 * 36 * 36;

    private TestColors() {
    }

    public static long insert(JdbcTemplate jdbc, String name) {
        for (int attempt = 0; attempt < CAPACITY; attempt++) {
            long sequence = jdbc.queryForObject(
                    "SELECT nextval(pg_get_serial_sequence('bas_color', 'id'))", Long.class);
            int value = (int) (sequence % CAPACITY);
            String code = "" + DIGITS.charAt(value / (36 * 36))
                    + DIGITS.charAt(value / 36 % 36) + DIGITS.charAt(value % 36);
            List<Long> ids = jdbc.queryForList("""
                    INSERT INTO bas_color (id, code, name) OVERRIDING SYSTEM VALUE VALUES (?, ?, ?)
                    ON CONFLICT (code) DO NOTHING RETURNING id""", Long.class, sequence, code, name);
            if (!ids.isEmpty()) return ids.getFirst();
        }
        throw new IllegalStateException("No unused three-character color code for test fixture");
    }
}
