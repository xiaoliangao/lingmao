package com.lingmao.scm.module.system.service.impl;

import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.module.system.dto.MenuSaveReq;
import com.lingmao.scm.module.system.entity.SysMenu;
import com.lingmao.scm.module.system.mapper.SysMenuMapper;
import com.lingmao.scm.module.system.mapper.SysRoleMenuMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MenuServiceImplTest {

    @Mock
    private SysMenuMapper sysMenuMapper;

    @Mock
    private SysRoleMenuMapper sysRoleMenuMapper;

    @InjectMocks
    private MenuServiceImpl menuService;

    /** 规则：有下级的菜单不能删，也不能去清角色关联 */
    @Test
    void delete_hasChildren_throwsAndNotDelete() {
        when(sysMenuMapper.selectById(1L)).thenReturn(menu(1L, 0L));
        when(sysMenuMapper.exists(any())).thenReturn(true);

        assertThatThrownBy(() -> menuService.delete(1L))
                .isInstanceOf(BizException.class)
                .hasMessage("有下级菜单，不能删除");
        verify(sysMenuMapper, never()).deleteById(any(Long.class));
        verify(sysRoleMenuMapper, never()).delete(any());
    }

    /** 规则：上级不能改成自己的下级。1 → 2 → 3，把 1 的上级改成 3，就成了环 */
    @Test
    void update_parentIsOwnDescendant_throwsAndNotUpdate() {
        when(sysMenuMapper.selectById(1L)).thenReturn(menu(1L, 0L));
        when(sysMenuMapper.selectById(3L)).thenReturn(menu(3L, 2L));
        when(sysMenuMapper.selectById(2L)).thenReturn(menu(2L, 1L));

        assertThatThrownBy(() -> menuService.update(1L, dirReq(3L)))
                .isInstanceOf(BizException.class)
                .hasMessage("上级菜单不能是自己或自己的下级");
        verify(sysMenuMapper, never()).updateById(any(SysMenu.class));
    }

    /** 规则：按钮必须有权限标识，不然它什么也管不了 */
    @Test
    void create_buttonWithoutPerms_throws() {
        MenuSaveReq req = new MenuSaveReq();
        req.setParentId(2L);
        req.setName("新增");
        req.setType(SysMenu.TYPE_BUTTON);

        assertThatThrownBy(() -> menuService.create(req))
                .isInstanceOf(BizException.class)
                .hasMessage("按钮必须填权限标识");
        verify(sysMenuMapper, never()).insert(any(SysMenu.class));
    }

    /** 规则：管理后台目录下面新建的菜单，也属于管理后台。请求里传了 false 也不算数 */
    @Test
    void create_underAdminOnlyParent_inheritsAdminOnly() {
        SysMenu parent = menu(1L, 0L);
        parent.setAdminOnly(true);
        when(sysMenuMapper.selectById(1L)).thenReturn(parent);
        MenuSaveReq req = new MenuSaveReq();
        req.setParentId(1L);
        req.setName("系统配置");
        req.setType(SysMenu.TYPE_MENU);
        req.setPath("config");
        req.setComponent("system/config/index");
        req.setAdminOnly(false);

        menuService.create(req);

        ArgumentCaptor<SysMenu> saved = ArgumentCaptor.forClass(SysMenu.class);
        verify(sysMenuMapper).insert(saved.capture());
        assertThat(saved.getValue().getAdminOnly()).isTrue();
    }

    /** 规则：下面还有菜单的目录，不能在管理后台和工作台之间移动（不然下级的标记就和它对不上了） */
    @Test
    void update_switchSideWithChildren_throwsAndNotUpdate() {
        SysMenu dir = menu(1L, 0L);
        dir.setAdminOnly(true);
        when(sysMenuMapper.selectById(1L)).thenReturn(dir);
        when(sysMenuMapper.exists(any())).thenReturn(true);
        MenuSaveReq req = dirReq(0L);
        req.setAdminOnly(false);

        assertThatThrownBy(() -> menuService.update(1L, req))
                .isInstanceOf(BizException.class)
                .hasMessage("下面还有菜单，不能在管理后台和工作台之间移动");
        verify(sysMenuMapper, never()).updateById(any(SysMenu.class));
    }

    private MenuSaveReq dirReq(Long parentId) {
        MenuSaveReq req = new MenuSaveReq();
        req.setParentId(parentId);
        req.setName("系统管理");
        req.setType(SysMenu.TYPE_DIR);
        req.setPath("/system");
        return req;
    }

    private SysMenu menu(Long id, Long parentId) {
        SysMenu m = new SysMenu();
        m.setId(id);
        m.setParentId(parentId);
        return m;
    }
}
