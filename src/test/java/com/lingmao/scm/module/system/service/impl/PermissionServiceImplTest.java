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
}
