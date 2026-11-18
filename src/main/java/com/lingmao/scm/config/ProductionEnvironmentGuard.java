package com.lingmao.scm.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Profiles;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.Locale;

/** 在读完配置之后、建数据库连接、Redis、HTTP 服务之前执行。 */
public final class ProductionEnvironmentGuard implements EnvironmentPostProcessor, Ordered {
    @Override public int getOrder() { return Ordered.LOWEST_PRECEDENCE; }
    @Override public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication application) { validate(env); }
    static void validate(ConfigurableEnvironment env) {
        if (!env.acceptsProfiles(Profiles.of("prod"))) return;
        if (Arrays.stream(env.getActiveProfiles()).anyMatch(p -> p.equals("local") || p.equals("it") || p.equals("test") || p.contains("mock")))
            fail("SPRING_PROFILES_ACTIVE");
        require(env,"spring.datasource.url"); require(env,"spring.datasource.username");
        require(env,"spring.datasource.password"); require(env,"spring.data.redis.host"); require(env,"spring.data.redis.password");
        String jwt = require(env,"lingmao.jwt.secret");
        try { if (!strong(Base64.getDecoder().decode(jwt),32)) fail("JWT_SECRET"); }
        catch (IllegalArgumentException invalid) { fail("JWT_SECRET"); }
        String file = require(env,"lingmao.upload.sign-key");
        if (!file.matches("[A-Za-z0-9_-]{32,256}") || !strong(file.getBytes(StandardCharsets.UTF_8),32)) fail("FILE_SIGN_KEY");
        String password = env.getProperty("lingmao.init-admin-password", "");
        if (!password.isBlank() && (password.length() < 16 || password.getBytes(StandardCharsets.UTF_8).length > 72
                || !strong(password.getBytes(StandardCharsets.UTF_8),16))) fail("INIT_ADMIN_PASSWORD");
        for (String flag : new String[]{"debug","trace","spring.devtools.restart.enabled","springdoc.api-docs.enabled", "springdoc.swagger-ui.enabled"})
            if (env.getProperty(flag,Boolean.class,false)) fail(flag);
        if (!env.getProperty("spring.flyway.enabled",Boolean.class,true)
                || !env.getProperty("spring.flyway.clean-disabled",Boolean.class,true)) fail("spring.flyway");
        if (!env.getProperty("spring.jackson.deserialization.fail-on-unknown-properties",Boolean.class,false)) fail("strict JSON schema");
        if (!"org.apache.ibatis.logging.nologging.NoLoggingImpl".equals(env.getProperty("mybatis-plus.configuration.log-impl"))) fail("SQL parameter logging");
        Binder.get(env).bind("logging.level", Bindable.mapOf(String.class, String.class)).orElseGet(java.util.Map::of)
                .forEach((logger, configured) -> {
                    String level = configured.trim().toUpperCase(Locale.ROOT);
                    if (level.equals("DEBUG") || level.equals("TRACE")) fail("logging.level." + logger);
                });
        if (env.containsProperty("logging.config")) fail("logging.config override");
    }
    private static String require(ConfigurableEnvironment env, String key) {
        try {
            String value = env.getProperty(key);
            if (value == null || value.isBlank() || value.contains("${")) fail(key);
            return value;
        } catch (IllegalArgumentException unresolved) { throw new IllegalStateException("Production configuration rejected: " + key); }
    }
    private static boolean strong(byte[] bytes,int min) {
        if (bytes.length < min) return false;
        var chars = new java.util.HashSet<Byte>(); for (byte b:bytes) chars.add(b);
        if (chars.size() < 8) return false;
        String lower = new String(bytes, StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
        for (String weak : new String[]{"dev-secret","change-me","changeme","password","123456","example","test-secret"})
            if (lower.contains(weak)) return false;
        for (int period=1; period <= Math.min(16,bytes.length/2); period++) {
            boolean repeated=true;
            for (int i=period;i<bytes.length;i++) if (bytes[i]!=bytes[i%period]) { repeated=false; break; }
            if (repeated) return false;
        }
        return true;
    }
    private static void fail(String key) { throw new IllegalStateException("Production configuration rejected: " + key); }
}
