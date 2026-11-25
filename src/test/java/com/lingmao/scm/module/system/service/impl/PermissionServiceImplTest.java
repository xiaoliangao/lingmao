package com.lingmao.scm.module.system.service.impl;

import com.lingmao.scm.module.system.entity.SysUser;
import com.lingmao.scm.module.system.mapper.SysMenuMapper;
import com.lingmao.scm.module.system.mapper.SysRoleMapper;
import com.lingmao.scm.module.system.mapper.SysUserMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PermissionServiceImplTest {

    @Mock
    private SysUserMapper sysUserMapper;

    @Mock
    private SysRoleMapper sysRoleMapper;

    @Mock
    private SysMenuMapper sysMenuMapper;

    @InjectMocks
    private PermissionServiceImpl permissionService;

    /** 规则：停用的用户返回 null（过滤器据此不认他），也不用再查权限 */
    @Test
    void loadPermissions_disabledUser_returnsNull() {
        SysUser user = new SysUser();
        user.setId(2L);
        user.setStatus(0);
        when(sysUserMapper.selectById(2L)).thenReturn(user);

        assertThat(permissionService.loadPermissions(2L)).isNull();
        verify(sysMenuMapper, never()).selectPermsByUserId(any());
    }

    /** 规则（RFC 018 §2）：要先改密码的账号一个业务权限都不给，只有"要改密码"的标识，也不去查菜单 */
    @Test
    void loadPermissions_mustChangePassword_onlyMarker() {
        SysUser user = new SysUser();
        user.setId(2L);
        user.setStatus(1);
        user.setPwdMustChange(true);
        when(sysUserMapper.selectById(2L)).thenReturn(user);

        assertThat(permissionService.loadPermissions(2L)).containsExactly(com.lingmao.scm.common.auth.CallerScope.MUST_CHANGE_PASSWORD);
        verify(sysMenuMapper, never()).selectPermsByUserId(any());
    }

    /** 规则：启用的用户返回他的权限标识 */
    @Test
    void loadPermissions_enabledUser_returnsPerms() {
        SysUser user = new SysUser();
        user.setId(2L);
        user.setStatus(1);
        when(sysUserMapper.selectById(2L)).thenReturn(user);
        when(sysMenuMapper.selectPermsByUserId(2L)).thenReturn(List.of("system:user:list", "system:user:add"));

        assertThat(permissionService.loadPermissions(2L)).containsExactly("system:user:list", "system:user:add");
    }

    /**
     * 规则：数据范围是"全部"的多一个 data:all（哪些角色算"全部"由 SQL 判断，在 PermissionIT 里测）；
     * 品类线每条一个 line:XXX（RFC 013 §2）
     */
    @Test
    void loadPermissions_allDataScope_addsDataAllAndLines() {
        SysUser user = new SysUser();
        user.setId(2L);
        user.setStatus(1);
        user.setProductLines(List.of("FOOTWEAR", "GENERAL"));
        when(sysUserMapper.selectById(2L)).thenReturn(user);
        when(sysMenuMapper.selectPermsByUserId(2L)).thenReturn(List.of("system:user:list"));
        when(sysRoleMapper.selectScopeFlags(2L)).thenReturn(new SysRoleMapper.ScopeFlags(true, false, false));

        assertThat(permissionService.loadPermissions(2L))
                .containsExactly("system:user:list", "data:all", "line:FOOTWEAR", "line:GENERAL");
    }

    /** 规则：超级管理员看全部品类线（line:all），不看自己那一列 */
    @Test
    void loadPermissions_admin_allLines() {
        SysUser user = new SysUser();
        user.setId(1L);
        user.setStatus(1);
        user.setProductLines(List.of("APPAREL"));
        when(sysUserMapper.selectById(1L)).thenReturn(user);
        when(sysMenuMapper.selectPermsByUserId(1L)).thenReturn(List.of());
        when(sysRoleMapper.selectScopeFlags(1L)).thenReturn(new SysRoleMapper.ScopeFlags(true, true, false));

        assertThat(permissionService.loadPermissions(1L)).containsExactly("data:all", "line:all");
    }

    /** 规则（用户 11-21 定）：有一个启用的角色勾了"审核不受数据范围限制"，多给 data:audit-any，不给 data:all */
    @Test
    void loadPermissions_auditAnyScope_addsAuditAny() {
        SysUser user = new SysUser();
        user.setId(3L);
        user.setStatus(1);
        when(sysUserMapper.selectById(3L)).thenReturn(user);
        when(sysMenuMapper.selectPermsByUserId(3L)).thenReturn(List.of("inventory:doc:audit"));
        when(sysRoleMapper.selectScopeFlags(3L)).thenReturn(new SysRoleMapper.ScopeFlags(false, false, true));

        assertThat(permissionService.loadPermissions(3L)).containsExactly("inventory:doc:audit", "data:audit-any");
    }
}
