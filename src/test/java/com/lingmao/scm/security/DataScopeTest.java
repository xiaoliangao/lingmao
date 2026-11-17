package com.lingmao.scm.security;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.module.system.entity.RoleDataScope;
import com.lingmao.scm.module.system.entity.SysOperLog;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 数据范围（PRD §3.2）：本人的只看自己相关的，"全部"的人能切到"我的"。随便拿一个有两个 xxx_id 列的实体当单据（sys_oper_log），
 * 只看拼出来的 SQL 片段，不碰库
 */
class DataScopeTest {

    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), SysOperLog.class);
    }

    @AfterEach
    void logout() {
        SecurityContextHolder.clearContext();
    }

    /** 规则：本人范围加 AND (列1 = 我 OR 列2 = 我)，括号包住，不会把前面的条件 OR 掉 */
    @Test
    void self_addsOrOfOwnerColumns() {
        login(7L);
        DataScope scope = DataScope.current(false);
        LambdaQueryWrapper<SysOperLog> w = new LambdaQueryWrapper<SysOperLog>().eq(SysOperLog::getModule, "订单");

        scope.apply(w, SysOperLog::getUserId, SysOperLog::getId);

        assertThat(scope.isSelf()).isTrue();
        assertThat(w.getSqlSegment()).isEqualTo("(module = #{ew.paramNameValuePairs.MPGENVAL1} AND (user_id = #{ew.paramNameValuePairs.MPGENVAL2} OR id = #{ew.paramNameValuePairs.MPGENVAL3}))");
        assertThat(w.getParamNameValuePairs()).containsEntry("MPGENVAL2", 7L).containsEntry("MPGENVAL3", 7L);
        assertThat(scope.canSee(null, 8L)).isFalse();
        assertThat(scope.canSee(null, 7L)).isTrue();
        assertThatThrownBy(() -> scope.check(8L)).isInstanceOf(BizException.class).extracting("code").isEqualTo(404);
    }

    /** 规则：有 data:all 的不加条件、谁的都能看；选了"我的"（mine = true）就和本人一样 */
    @Test
    void all_noCondition_unlessMine() {
        login(7L, RoleDataScope.ALL_AUTHORITY);
        LambdaQueryWrapper<SysOperLog> w = new LambdaQueryWrapper<>();

        DataScope.current(false).apply(w, SysOperLog::getUserId);

        assertThat(w.getSqlSegment()).isEmpty();
        assertThat(DataScope.current(false).canSee(8L)).isTrue();
        assertThat(DataScope.current(true).isSelf()).isTrue();
        assertThat(DataScope.current(true).canSee(8L)).isFalse();
    }

    /** 规则：没登录算本人、什么都看不到（列 = NULL 不成立，这里直接 1 = 0） */
    @Test
    void anonymous_seesNothing() {
        LambdaQueryWrapper<SysOperLog> w = new LambdaQueryWrapper<>();

        DataScope.current(false).apply(w, SysOperLog::getUserId);

        assertThat(w.getSqlSegment()).contains("1 = 0");
        assertThat(DataScope.current(false).canSee((Long) null)).isFalse();
    }

    private static void login(long userId, String... authorities) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new LoginUser(userId, "jti"), null, Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList()));
    }
}
