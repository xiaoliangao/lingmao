package com.lingmao.scm.security;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.module.system.entity.SysOperLog;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 品类线（RFC 013 §2）：从当前请求的 line:XXX 权限读出能看哪几条，拼 IN 和 &amp;&amp; 两种条件。
 * 随便拿 sys_oper_log 的 module 列当单据的 product_line，只看拼出来的 SQL 片段，不碰库
 */
class LineScopeTest {

    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), SysOperLog.class);
    }

    @AfterEach
    void logout() {
        SecurityContextHolder.clearContext();
    }

    /** 规则：几条线的人加 IN (这几条)、数组列加 &amp;&amp;；只能看这几条，null（没定线的）也看不到 */
    @Test
    void someLines_inAndOverlap() {
        login("order:order:list", "line:FOOTWEAR", "line:GENERAL");
        LineScope lines = LineScope.current();
        LambdaQueryWrapper<SysOperLog> w = new LambdaQueryWrapper<>();
        QueryWrapper<SysOperLog> any = new QueryWrapper<>();

        lines.apply(w, SysOperLog::getModule);
        lines.applyAny(any, "product_lines");

        assertThat(lines.isLimited()).isTrue();
        assertThat(lines.getValues()).containsExactly("FOOTWEAR", "GENERAL");
        assertThat(w.getSqlSegment()).isEqualTo("(module IN (#{ew.paramNameValuePairs.MPGENVAL1},#{ew.paramNameValuePairs.MPGENVAL2}))");
        assertThat(any.getSqlSegment()).isEqualTo(
                "(product_lines && ARRAY[#{ew.paramNameValuePairs.MPGENVAL1},#{ew.paramNameValuePairs.MPGENVAL2}]::varchar[])");
        assertThat(lines.canSee("FOOTWEAR")).isTrue();
        assertThat(lines.canSee("APPAREL")).isFalse();
        assertThat(lines.canSee(null)).isFalse();
        assertThat(lines.canSeeAny(List.of("APPAREL", "GENERAL"))).isTrue();
        assertThat(lines.canSeeAny(List.of("APPAREL"))).isFalse();
        assertThat(lines.only()).isNull();
        assertThat(lines.first()).isEqualTo("FOOTWEAR");
        assertThatThrownBy(() -> lines.check("APPAREL")).isInstanceOf(BizException.class).extracting("code").isEqualTo(404);
    }

    /** 规则：line:all（超级管理员）不加条件，什么线都能看；只有一条线的人 only() 是那条（手工采购单自动带上） */
    @Test
    void all_noCondition_andOnly() {
        login("line:all", "line:FOOTWEAR");
        LambdaQueryWrapper<SysOperLog> w = new LambdaQueryWrapper<>();

        LineScope.current().apply(w, SysOperLog::getModule);

        assertThat(w.getSqlSegment()).isEmpty();
        assertThat(LineScope.current().isLimited()).isFalse();
        assertThat(LineScope.current().canSee("APPAREL")).isTrue();
        assertThat(LineScope.current().only()).isNull();
        assertThat(LineScope.current().first()).isEqualTo(LineScope.APPAREL);

        login("line:FOOTWEAR");
        assertThat(LineScope.current().only()).isEqualTo("FOOTWEAR");
    }

    /** 规则：没登录、或者一条线都没有的，什么都看不到（1 = 0），不会因为没给就当成看全部 */
    @Test
    void noLines_seesNothing() {
        LambdaQueryWrapper<SysOperLog> w = new LambdaQueryWrapper<>();
        LineScope.current().apply(w, SysOperLog::getModule);
        assertThat(w.getSqlSegment()).contains("1 = 0");

        login("order:order:list");
        QueryWrapper<SysOperLog> any = new QueryWrapper<>();
        LineScope.current().applyAny(any, "product_lines");
        assertThat(any.getSqlSegment()).contains("1 = 0");
        assertThat(LineScope.current().canSee("APPAREL")).isFalse();
    }

    private static void login(String... authorities) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new LoginUser(7L, "jti"), null, Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList()));
    }
}
