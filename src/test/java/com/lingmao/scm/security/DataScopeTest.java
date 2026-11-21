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

    /**
     * 规则（用户 11-21 定）：审核不受数据范围限制 = data:audit-any 加上这种单的审核权限，待审的单、自己审过的单不按本人筛；
     * 少一样、或者单不是待审也不是我审的，照旧本人
     */
    @Test
    void orAuditing_needsFlagAndAuditPermission() {
        login(7L, RoleDataScope.AUDIT_ANY_AUTHORITY, "inventory:doc:audit");
        assertThat(DataScope.current(false).orAuditing(true, null, "inventory:doc:audit").canSee(8L)).isTrue();
        assertThat(DataScope.current(false).orAuditing(false, 7L, "inventory:doc:audit").canSee(8L)).as("我审过的").isTrue();
        assertThat(DataScope.current(false).orAuditing(false, 9L, "inventory:doc:audit").canSee(8L)).as("不是待审也不是我审的").isFalse();
        assertThat(DataScope.current(false).orAuditing(true, null, "inventory:count:audit").canSee(8L)).as("没有这种单的审核权限").isFalse();

        login(7L, "inventory:doc:audit");
        assertThat(DataScope.current(false).orAuditing(true, null, "inventory:doc:audit").canSee(8L)).as("角色没勾").isFalse();
    }

    /**
     * 规则：单级审核单据的待我审核（forSingleAudit）不看 mine；没有这种单的审核权限的一条都没有（和首页计数一样）；
     * 不是待我审核时照 current(mine)
     */
    @Test
    void forSingleAudit_noAuditPermissionSeesNothing() {
        login(7L, RoleDataScope.ALL_AUTHORITY, "inventory:doc:list");
        DataScope none = DataScope.forSingleAudit(false, true, "inventory:doc:audit");
        assertThat(none.isSelf()).isTrue();
        assertThat(none.getUserId()).isNull();
        assertThat(none.canSee(7L)).isFalse();
        assertThat(DataScope.forSingleAudit(true, false, "inventory:doc:audit").isSelf()).as("我的").isTrue();

        login(7L, RoleDataScope.ALL_AUTHORITY, "inventory:doc:audit");
        assertThat(DataScope.forSingleAudit(true, true, "inventory:doc:audit").isAll()).as("待我审核不看 mine").isTrue();
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
