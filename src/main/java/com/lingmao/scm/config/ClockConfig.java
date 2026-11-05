package com.lingmao.scm.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

/**
 * "现在几点"统一从这个 Clock 取（东八区）：代码里写 LocalDate.now(clock)、clock.instant()，不写 LocalDate.now()。
 * 为什么：测试里换成 Clock.fixed(...)，"今天"、"36 小时前"就是可控的；业务日期也不跟着服务器时区走（RFC 001 §6.2）。
 * 全系统只有这一个 Clock，别的地方不要再建。
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.system(ZoneId.of("Asia/Shanghai"));
    }
}
