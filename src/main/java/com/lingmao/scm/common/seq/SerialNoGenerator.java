package com.lingmao.scm.common.seq;

import com.lingmao.scm.common.exception.BizException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 单号和编码的流水号，计数在 PostgreSQL 的 sys_serial 表里（架构 §4.5、RFC 001 §6.1）：
 * <ul>
 *   <li>daily("CG")：单据号，前缀 + yyyyMMdd + 4 位当天流水，如 CG202611050001</li>
 *   <li>next("GYS", 4)：主数据编码，前缀 + 流水，不分天，如 GYS0008</li>
 *   <li>peek(key)：下一个号是几，只看不取（款号预览）；raiseTo(key, n)：导入老编码后把流水推到 n</li>
 * </ul>
 * 为什么不用 Redis 的 INCR：Redis 重启没持久化、有人删了 key，当天的号就从 0001 重来，单号重复比缺号麻烦得多。
 * <p>
 * 取号用 JdbcTemplate，和 MyBatis 是同一个数据源，自动加入调用方的事务：建单失败，号也一起回滚，单号连续不跳。
 * 代价是同一个 key 的取号要排队（行锁持有到事务提交），20 个人用感觉不到。
 * 所以取号的方法（还有 raiseTo）都要求已经在事务里（MANDATORY）：不在事务里，号一取就提交了，后面建单失败号就白白跳过，
 * 这种错平时看不出来，干脆调用时就报错。调用方的建单方法本来就该是 @Transactional。
 * <p>
 * 取到的号已经被手填的、从领猫导入的编码占了，由各模块再取下一个（RFC 001 §6.1），各表 code / no 的唯一索引兜底。
 */
@Component
@RequiredArgsConstructor
public class SerialNoGenerator {

    private static final DateTimeFormatter DAY = DateTimeFormatter.BASIC_ISO_DATE;   // yyyyMMdd

    /** 第一次取插入 1，以后每次 +1，一条语句完成，返回这次取到的号。并发时 ON CONFLICT 保证只有一行、号不重复 */
    private static final String TAKE = """
            INSERT INTO sys_serial (seq_key, seq) VALUES (?, 1)
            ON CONFLICT (seq_key) DO UPDATE SET seq = sys_serial.seq + 1, update_time = now()
            RETURNING seq
            """;

    /** 推到 n：没有这一行就插入 n，有就取两者大的，只往上推不往下拉 */
    private static final String RAISE = """
            INSERT INTO sys_serial (seq_key, seq) VALUES (?, ?)
            ON CONFLICT (seq_key) DO UPDATE SET seq = GREATEST(sys_serial.seq, EXCLUDED.seq), update_time = now()
            """;

    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    /** 单据号：prefix + 今天 + 4 位当天流水。prefix 见 PRD §5.2，如 CG 采购单、RK 入库单 */
    @Transactional(propagation = Propagation.MANDATORY)
    public String daily(String prefix) {
        return next(prefix + LocalDate.now(clock).format(DAY), 4);
    }

    /**
     * key + width 位流水：主数据编码 next("GYS", 4) → GYS0008；款号 next("FS26110807", 2) → FS2611080701。
     * 流水超过 width 位（9999、99）报 1000：编码是定长的，多一位就不是这个格式了
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public String next(String key, int width) {
        Integer seq = jdbcTemplate.queryForObject(TAKE, Integer.class, key);
        if (String.valueOf(seq).length() > width) {
            // 抛出去调用方的事务回滚，这次 +1 也一起撤销
            throw new BizException("编号 " + key + " 的流水号用完了（最多 " + width + " 位）");
        }
        return format(key, seq, width);
    }

    /**
     * 下一个号是几，只看不取（不加锁、不用在事务里）：款号预览用（RFC 005 §3.1），点几次都不跳号。
     * 返回数字不返回编码：预览要跳过已经被手填占了的号，调用方从这个数往上试，拼编码用 format。
     * 预览出来的号保存时不一定还是它（同一时间别人也建了），以 next 真取到的为准
     */
    public int peek(String key) {
        List<Integer> seq = jdbcTemplate.queryForList("SELECT seq FROM sys_serial WHERE seq_key = ?", Integer.class, key);
        return seq.isEmpty() ? 1 : seq.getFirst() + 1;
    }

    /**
     * 把流水推到至少 n，下一次 next 从 n + 1 开始；已经比 n 大就不动（RFC 003 §7.1）。
     * 导入领猫的老编码以后用：按前缀算出导入的编码里最大的流水，推上去，之后新建的不会撞上导入的。
     * 和取号一样要在导入的事务里：导入失败回滚，推上去的也一起撤销
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void raiseTo(String key, int n) {
        if (n < 0) {
            throw new IllegalArgumentException("流水号不能是负数：" + n);
        }
        jdbcTemplate.update(RAISE, key, n);
    }

    /** key + width 位流水，不够补 0：format("GYS", 8, 4) → GYS0008 */
    public static String format(String key, int seq, int width) {
        return key + String.format("%0" + width + "d", seq);
    }
}
