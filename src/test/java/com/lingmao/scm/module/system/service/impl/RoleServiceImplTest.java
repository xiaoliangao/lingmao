package com.lingmao.scm.module.system.service.impl;

import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.module.system.entity.SysRole;
import com.lingmao.scm.module.system.mapper.SysMenuMapper;
import com.lingmao.scm.module.system.mapper.SysRoleMapper;
import com.lingmao.scm.module.system.mapper.SysRoleMenuMapper;
import com.lingmao.scm.module.system.mapper.SysUserRoleMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RoleServiceImplTest {

    @Mock
    private SysRoleMapper sysRoleMapper;

    @Mock
    private SysRoleMenuMapper sysRoleMenuMapper;

    @Mock
    private SysUserRoleMapper sysUserRoleMapper;

    @Mock
    private SysMenuMapper sysMenuMapper;

    @InjectMocks
    private RoleServiceImpl roleService;

    /** 规则：超级管理员角色不能分配菜单（它本来就拥有全部），也不能清掉它的关联 */
    @Test
    void assignMenus_adminRole_throwsAndNotTouchLinks() {
        when(sysRoleMapper.selectById(1L)).thenReturn(role(1L, "admin"));

        assertThatThrownBy(() -> roleService.assignMenus(1L, List.of(2L, 3L)))
                .isInstanceOf(BizException.class)
                .hasMessage("超级管理员角色不能修改、删除或分配菜单");
        verify(sysRoleMenuMapper, never()).delete(any());
        verify(sysRoleMenuMapper, never()).insert(anyList());
    }

    /** 规则：已经分配给用户的角色不能删 */
    @Test
    void delete_assignedToUser_throwsAndNotDelete() {
        when(sysRoleMapper.selectById(2L)).thenReturn(role(2L, "keeper"));
        when(sysUserRoleMapper.exists(any())).thenReturn(true);

        assertThatThrownBy(() -> roleService.delete(2L))
                .isInstanceOf(BizException.class)
                .hasMessage("角色已分配给用户，不能删除");
        verify(sysRoleMapper, never()).deleteById(any(Long.class));
    }

    private SysRole role(Long id, String code) {
        SysRole r = new SysRole();
        r.setId(id);
        r.setCode(code);
        return r;
    }
}
