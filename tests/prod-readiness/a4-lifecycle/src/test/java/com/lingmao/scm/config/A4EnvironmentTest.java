package com.lingmao.scm.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class A4EnvironmentTest {
    @Test void strongProductionConfigurationPasses() { assertThatCode(()->ProductionEnvironmentGuard.validate(valid())).doesNotThrowAnyException(); }
    @Test void missingWeakKeysDebugAndTestProfilesFailBeforeConnecting() {
        for (var entry:Map.of("lingmao.jwt.secret",Base64.getEncoder().encodeToString("dev-secret".repeat(8).getBytes()),
                "lingmao.upload.sign-key","abcdefgh".repeat(4), "lingmao.init-admin-password","Password1234567890",
                "debug","true","springdoc.api-docs.enabled","true","spring.flyway.clean-disabled","false",
                "spring.datasource.password","", "spring.data.redis.password","", "mybatis-plus.configuration.log-impl","StdOutImpl").entrySet()) {
            var env=valid().withProperty(entry.getKey(),entry.getValue());
            assertThatThrownBy(()->ProductionEnvironmentGuard.validate(env)).as(entry.getKey()).isInstanceOf(IllegalStateException.class);
        }
        var mixed=valid();mixed.setActiveProfiles("prod","test");
        assertThatThrownBy(()->ProductionEnvironmentGuard.validate(mixed)).isInstanceOf(IllegalStateException.class);
        for (var entry: Map.of("logging.level.com.lingmao.scm", "DEBUG", "logging.level.org.springframework.web", "TRACE",
                "lingmao.init-admin-password", "测试多字节初始化口令不得超过字节长度abcdefghijklmno0123456789".repeat(2)).entrySet()) {
            var env=valid().withProperty(entry.getKey(),entry.getValue());
            assertThatThrownBy(()->ProductionEnvironmentGuard.validate(env)).as(entry.getKey()).isInstanceOf(IllegalStateException.class);
        }
    }
    private MockEnvironment valid() {
        byte[] key=new byte[48];new SecureRandom().nextBytes(key);
        var env=new MockEnvironment();env.setActiveProfiles("prod");
        return env.withProperty("spring.datasource.url","jdbc:postgresql://127.0.0.1:5433/scm_test")
                .withProperty("spring.datasource.username","scm").withProperty("spring.datasource.password","ephemeral")
                .withProperty("spring.data.redis.host","127.0.0.1").withProperty("spring.data.redis.password","ephemeral")
                .withProperty("lingmao.jwt.secret",Base64.getEncoder().encodeToString(key))
                .withProperty("lingmao.upload.sign-key",java.util.HexFormat.of().formatHex(key))
                .withProperty("spring.jackson.deserialization.fail-on-unknown-properties","true")
                .withProperty("mybatis-plus.configuration.log-impl","org.apache.ibatis.logging.nologging.NoLoggingImpl");
    }
}
