package com.lingmao.scm;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 数据库集成测试（RFC 001 §11.2、架构 §13）：类名以 IT 结尾、贴这个注解。mvn verify 时由 failsafe 跑，
 * 起整个应用，连本机 Docker 里的 scm_test（application-it.yaml）；mvn test 不跑它们。
 * 为什么要真库：条件更新、ON CONFLICT、RETURNING、部分唯一索引、递归查询、外键报错的原文、行锁，Mockito 都测不出来。
 * <p>
 * 启动时先查库名必须以 _test 结尾，再清空、重新执行全部迁移（IntegrationTestConfig）。
 * 所有 IT 都用这一个注解、不再各加 @MockitoBean 之类的东西：配置一样，Spring 只起一次应用，大家共用。
 * <p>
 * <b>数据怎么隔离</b>：不用 @Transactional 让每个测试回滚，每个测试自己造数据、用随机编码，不依赖表是空的；
 * 上一次跑剩下的，下一次启动时整库清掉。为什么不回滚：
 * <ul>
 *   <li>要测的恰恰是事务本身：并发取号要每个线程各自提交、互相等行锁；"建单失败号也回滚"要看提交以后的结果。
 *       整个测试包在一个事务里，这些都看不到</li>
 *   <li>PostgreSQL 的事务里一出错（外键、唯一冲突），后面的语句全部报 "current transaction is aborted"，
 *       测完报错就没法再查库断言</li>
 *   <li>测试里调的 Service 方法自己就是 @Transactional，包一层外面的事务会让它们的提交、回滚行为和线上不一样</li>
 * </ul>
 * 也不在测试之间清表：迁移插入的菜单、admin 角色和启动时建的 admin 别的测试要用，随机编码已经够隔离。
 * 建了表、函数这种库结构的测试，测完自己删掉。
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest
@ActiveProfiles("it")
@Import(IntegrationTestConfig.class)
public @interface IntegrationTest {
}
