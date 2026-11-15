package com.lingmao.scm.config;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.lingmao.scm.common.entity.AuditEntity;
import com.lingmao.scm.security.LoginUser;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.reflection.SystemMetaObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 审计列自动填充（RFC 001 §4.1）：人从登录信息取，时间从 Clock 取 */
class AuditMetaObjectHandlerTest {

    private static final Instant NOW = Instant.parse("2026-11-15T02:00:00Z");
    private static final OffsetDateTime OLD = OffsetDateTime.parse("2026-11-01T08:00:00+08:00");

    private final AuditMetaObjectHandler handler = new AuditMetaObjectHandler(Clock.fixed(NOW, ZoneId.of("Asia/Shanghai")));

    /** strictInsertFill 要从表信息里查哪些字段带填充，单元测试里手动建 */
    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Thing.class);
    }

    @AfterEach
    void logout() {
        SecurityContextHolder.clearContext();
    }

    /** 规则：插入时四列都填，人是当前登录的用户，时间是 Clock 的现在 */
    @Test
    void insert_fillsAllFour() {
        login(5L);
        Thing thing = new Thing();

        handler.insertFill(SystemMetaObject.forObject(thing));

        assertThat(thing.getCreateBy()).isEqualTo(5L);
        assertThat(thing.getUpdateBy()).isEqualTo(5L);
        assertThat(thing.getCreateTime().toInstant()).isEqualTo(NOW);
        assertThat(thing.getUpdateTime().toInstant()).isEqualTo(NOW);
    }

    /** 规则：没有登录用户（启动时建 admin、定时任务），人留空，时间照填 */
    @Test
    void insert_noLogin_byIsNull() {
        Thing thing = new Thing();

        handler.insertFill(SystemMetaObject.forObject(thing));

        assertThat(thing.getCreateBy()).isNull();
        assertThat(thing.getUpdateBy()).isNull();
        assertThat(thing.getCreateTime().toInstant()).isEqualTo(NOW);
    }

    /** 规则（bug 002）：修改是先查出来再写回去的，旧的修改时间、修改人要被覆盖，不能原样写回；创建的两列不动 */
    @Test
    void update_overwritesOldValues() {
        login(5L);
        Thing thing = loaded();

        handler.updateFill(SystemMetaObject.forObject(thing));

        assertThat(thing.getUpdateTime().toInstant()).isEqualTo(NOW);
        assertThat(thing.getUpdateBy()).isEqualTo(5L);
        assertThat(thing.getCreateTime()).isEqualTo(OLD);
        assertThat(thing.getCreateBy()).isEqualTo(9L);
    }

    /** 规则：没有登录用户时修改人覆盖成 null：系统改的就是系统改的，不能记成上一个改它的人 */
    @Test
    void update_noLogin_updateByCleared() {
        Thing thing = loaded();

        handler.updateFill(SystemMetaObject.forObject(thing));

        assertThat(thing.getUpdateBy()).isNull();
        assertThat(thing.getUpdateTime().toInstant()).isEqualTo(NOW);
    }

    private static void login(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(new LoginUser(userId, "jti"), null, List.of()));
    }

    /** 从库里查出来的样子：四列都有旧值，是 9 号用户建、改的 */
    private static Thing loaded() {
        Thing thing = new Thing();
        thing.setId(1L);
        thing.setCreateBy(9L);
        thing.setCreateTime(OLD);
        thing.setUpdateBy(9L);
        thing.setUpdateTime(OLD);
        return thing;
    }

    @Data
    @EqualsAndHashCode(callSuper = true)
    @TableName("biz_thing")
    static class Thing extends AuditEntity {
        private String name;
    }
}
