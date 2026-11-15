package com.lingmao.scm.common.seq;

import com.lingmao.scm.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 取号在真库上的行为（架构 §4.5、RFC 001 §6.1、§16）。前缀每个测试随机，互不影响 */
@IntegrationTest
class SerialNoGeneratorIT {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    @Autowired
    private SerialNoGenerator generator;

    @Autowired
    private TransactionTemplate tx;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private Clock clock;

    /** 规则：单号 = 前缀 + 今天 + 4 位流水，同一天依次 0001、0002（PRD §5.2）；主数据编码不分天 */
    @Test
    void daily_andNext_format() {
        String prefix = randomPrefix();
        String today = LocalDate.now(clock).format(DateTimeFormatter.BASIC_ISO_DATE);

        assertThat(inTx(() -> generator.daily(prefix))).isEqualTo(prefix + today + "0001");
        assertThat(inTx(() -> generator.daily(prefix))).isEqualTo(prefix + today + "0002");
        assertThat(inTx(() -> generator.next(prefix, 4))).isEqualTo(prefix + "0001");
    }

    /**
     * 规则：20 个线程同时取同一种单据的号，不重复、不跳：正好是 0001 到 0020。
     * 每个线程各自一个事务；第一次取号时这个 key 还没有行，20 个 INSERT 同时撞上，ON CONFLICT 保证只插一行、其余排队 +1
     */
    @Test
    void daily_concurrent_noDuplicateNoGap() throws Exception {
        String prefix = randomPrefix();
        String today = LocalDate.now(clock).format(DateTimeFormatter.BASIC_ISO_DATE);
        int threads = 20;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<String>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return inTx(() -> generator.daily(prefix));
                }));
            }
            start.countDown();
            List<String> numbers = new ArrayList<>();
            for (Future<String> future : futures) {
                numbers.add(future.get());
            }

            assertThat(numbers).containsExactlyInAnyOrderElementsOf(IntStream.rangeClosed(1, threads)
                    .mapToObj(n -> prefix + today + String.format("%04d", n)).toList());
        } finally {
            pool.shutdownNow();
        }
    }

    /** 规则：建单失败（事务回滚），取的号也一起回滚，下一张单拿到的还是这个号，单号连续（架构 §4.5） */
    @Test
    void next_rolledBack_numberReused() {
        String key = randomPrefix();

        assertThat(inTx(() -> generator.next(key, 4))).isEqualTo(key + "0001");
        String rolledBack = tx.execute(s -> {
            String no = generator.next(key, 4);
            s.setRollbackOnly();   // 相当于建单时后面出错了
            return no;
        });
        assertThat(rolledBack).isEqualTo(key + "0002");
        assertThat(inTx(() -> generator.next(key, 4))).isEqualTo(key + "0002");
        assertThat(jdbcTemplate.queryForObject("SELECT seq FROM sys_serial WHERE seq_key = ?", Integer.class, key))
                .isEqualTo(2);
    }

    /** 规则：日期从 Clock 取，换了一天流水从 0001 重新开始；前一天的计数不受影响 */
    @Test
    void daily_nextDay_restartsFromOne() {
        String prefix = randomPrefix();
        SerialNoGenerator day1 = new SerialNoGenerator(jdbcTemplate, fixedAt("2026-11-05T23:59:00+08:00"));
        SerialNoGenerator day2 = new SerialNoGenerator(jdbcTemplate, fixedAt("2026-11-06T00:01:00+08:00"));

        assertThat(inTx(() -> day1.daily(prefix))).isEqualTo(prefix + "202611050001");
        assertThat(inTx(() -> day1.daily(prefix))).isEqualTo(prefix + "202611050002");
        assertThat(inTx(() -> day2.daily(prefix))).isEqualTo(prefix + "202611060001");
        assertThat(inTx(() -> day1.daily(prefix))).isEqualTo(prefix + "202611050003");
    }

    /** 规则：不在事务里取号、推流水直接报错：号一取就提交了，后面建单失败就白白跳过一个号 */
    @Test
    void outsideTransaction_throws() {
        assertThatThrownBy(() -> generator.daily(randomPrefix()))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> generator.raiseTo(randomPrefix(), 5))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    /** 规则：peek 只看不取（款号预览，RFC 005 §3.1）：没取过是 1；看几次都一样；接着 next 取到的就是看到的那个。不用在事务里 */
    @Test
    void peek_doesNotTake() {
        String key = randomPrefix();

        assertThat(generator.peek(key)).isEqualTo(1);
        assertThat(inTx(() -> generator.next(key, 2))).isEqualTo(key + "01");
        assertThat(generator.peek(key)).isEqualTo(2);
        int seen = generator.peek(key);
        assertThat(seen).isEqualTo(2);
        assertThat(inTx(() -> generator.next(key, 2))).isEqualTo(SerialNoGenerator.format(key, seen, 2));
        assertThat(generator.peek(key)).isEqualTo(3);
    }

    /**
     * 规则：导入老编码后 raiseTo 把流水推上去，新建的从下一个开始、不撞导入的（RFC 003 §7.1）；
     * 只往上推不往下拉；没取过的 key 也能推；导入回滚，推上去的一起撤销
     */
    @Test
    void raiseTo_onlyRaises() {
        String key = randomPrefix();
        String fresh = randomPrefix();

        assertThat(inTx(() -> generator.next(key, 4))).isEqualTo(key + "0001");
        tx.executeWithoutResult(s -> generator.raiseTo(key, 37));
        assertThat(inTx(() -> generator.next(key, 4))).isEqualTo(key + "0038");
        tx.executeWithoutResult(s -> generator.raiseTo(key, 5));
        assertThat(inTx(() -> generator.next(key, 4))).isEqualTo(key + "0039");

        tx.executeWithoutResult(s -> generator.raiseTo(fresh, 12));
        assertThat(generator.peek(fresh)).isEqualTo(13);

        tx.executeWithoutResult(s -> {
            generator.raiseTo(key, 500);
            s.setRollbackOnly();   // 相当于导入时后面出错了
        });
        assertThat(inTx(() -> generator.next(key, 4))).isEqualTo(key + "0040");
    }

    /** 在一个新事务里取号，返回后事务已经提交：和建单方法上的 @Transactional 一样 */
    private String inTx(Supplier<String> take) {
        return tx.execute(s -> take.get());
    }

    /** 随机的 6 位大写字母前缀，测试之间不会撞 */
    private static String randomPrefix() {
        StringBuilder prefix = new StringBuilder();
        for (int i = 0; i < 6; i++) {
            prefix.append((char) ('A' + ThreadLocalRandom.current().nextInt(26)));
        }
        return prefix.toString();
    }

    private static Clock fixedAt(String isoOffsetDateTime) {
        return Clock.fixed(Instant.from(DateTimeFormatter.ISO_OFFSET_DATE_TIME.parse(isoOffsetDateTime)), ZONE);
    }
}
