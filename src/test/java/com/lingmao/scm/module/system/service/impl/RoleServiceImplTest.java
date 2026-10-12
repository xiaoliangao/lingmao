package com.lingmao.scm.module.system.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.module.system.entity.SysMenu;
import com.lingmao.scm.module.system.entity.SysRole;
import com.lingmao.scm.module.system.mapper.SysMenuMapper;
import com.lingmao.scm.module.system.mapper.SysRoleMapper;
import com.lingmao.scm.module.system.mapper.SysRoleMenuMapper;
import com.lingmao.scm.module.system.mapper.SysUserRoleMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
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

    /**
     * new LambdaQueryWrapper<SysMenu>().in(SysMenu::getId, ...) 要从 MyBatis-Plus 的"表信息缓存"里查
     * getId 对应哪一列。启动 Spring 时它自动建好；单元测试不启动 Spring，缓存是空的，会报
     * "can not find lambda cache for this entity"。这里手动给 SysMenu 建一份
     */
    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), SysMenu.class);
    }

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

    /** 规则：管理后台的菜单只给超级管理员，分配给别的角色直接拒绝，原来的关联也不动 */
    @Test
    void assignMenus_adminOnlyMenu_throwsAndNotTouchLinks() {
        when(sysRoleMapper.selectById(2L)).thenReturn(role(2L, "keeper"));
        when(sysMenuMapper.selectCount(any())).thenReturn(2L);
        when(sysMenuMapper.exists(any())).thenReturn(true);

        assertThatThrownBy(() -> roleService.assignMenus(2L, List.of(2L, 3L)))
                .isInstanceOf(BizException.class)
                .hasMessage("管理后台的菜单只给超级管理员，不能分配给角色");
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
