package com.lingmao.scm.module.system.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.lingmao.scm.IntegrationTest;
import com.lingmao.scm.module.system.dto.RoleSaveReq;
import com.lingmao.scm.module.system.entity.RoleDataScope;
import com.lingmao.scm.module.system.entity.SysMenu;
import com.lingmao.scm.module.system.entity.SysUser;
import com.lingmao.scm.module.system.entity.SysUserRole;
import com.lingmao.scm.module.system.mapper.SysMenuMapper;
import com.lingmao.scm.module.system.mapper.SysUserMapper;
import com.lingmao.scm.module.system.mapper.SysUserRoleMapper;
import com.lingmao.scm.module.system.service.PermissionService;
import com.lingmao.scm.module.system.service.RoleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 权限加载在真库上：bug 009（停用上级，下级的权限也失效，靠递归查询）和数据范围 data:all（RFC 001 §8、§16）。
 * 每个测试建一棵自己的菜单树：目录 → 页面 → 按钮，权限标识带随机串，和别的测试、迁移里的菜单都不撞
 */
@IntegrationTest
class PermissionServiceImplIT {

    @Autowired
    private PermissionService permissionService;

    @Autowired
    private RoleService roleService;

    @Autowired
    private SysMenuMapper sysMenuMapper;

    @Autowired
    private SysUserMapper sysUserMapper;

    @Autowired
    private SysUserRoleMapper sysUserRoleMapper;

    private String tag;
    private SysMenu dir;
    private SysMenu page;
    private SysMenu button;
    private Long userId;

    @BeforeEach
    void setUp() {
        tag = UUID.randomUUID().toString().substring(0, 8);
        dir = menu(0L, SysMenu.TYPE_DIR, null);
        page = menu(dir.getId(), SysMenu.TYPE_MENU, "it" + tag + ":order:list");
        button = menu(page.getId(), SysMenu.TYPE_BUTTON, "it" + tag + ":order:add");
        userId = user();
    }

    /** 能用的时候：页面和按钮的权限都有，侧边栏有目录和页面 */
    @Test
    void allEnabled_hasPermsAndMenus() {
        grant(role(RoleDataScope.SELF, dir, page, button));

        assertThat(permissionService.loadPermissions(userId)).contains(page.getPerms(), button.getPerms());
        assertThat(sidebarIds()).contains(dir.getId(), page.getId());
    }

    /** bug 009：停用页面，按钮自己还是启用的，权限也跟着没了 */
    @Test
    void pageDisabled_buttonPermGone() {
        grant(role(RoleDataScope.SELF, dir, page, button));

        disable(page);

        assertThat(permissionService.loadPermissions(userId)).doesNotContain(page.getPerms(), button.getPerms());
        assertThat(sidebarIds()).contains(dir.getId()).doesNotContain(page.getId());
    }

    /** bug 009：停用目录，整棵子树的权限和侧边栏都没了，隔了两层的按钮也一样；超级管理员也看不到 */
    @Test
    void dirDisabled_wholeSubtreeGone() {
        grant(role(RoleDataScope.SELF, dir, page, button));

        disable(dir);

        assertThat(permissionService.loadPermissions(userId)).doesNotContain(page.getPerms(), button.getPerms());
        assertThat(sidebarIds()).doesNotContain(dir.getId(), page.getId());
        assertThat(permissionService.loadPermissions(adminId())).doesNotContain(page.getPerms(), button.getPerms());
    }

    /**
     * 数据范围：几个角色取最宽的。只有"本人"的角色没有 data:all；再给一个"全部"的就有；
     * 把那个"全部"的角色停用又没了。超级管理员一直有（V8 把 admin 设成了全部）
     */
    @Test
    void dataScope_widestEnabledRoleWins() {
        grant(role(RoleDataScope.SELF, page));
        assertThat(permissionService.loadPermissions(userId)).doesNotContain(RoleDataScope.ALL_AUTHORITY);

        Long allRole = role(RoleDataScope.ALL);
        grant(allRole);
        assertThat(roleService.getById(allRole).getDataScope()).isEqualTo(RoleDataScope.ALL);
        assertThat(permissionService.loadPermissions(userId)).contains(RoleDataScope.ALL_AUTHORITY);

        RoleSaveReq disabled = roleReq(roleService.getById(allRole).getCode(), RoleDataScope.ALL);
        disabled.setStatus(0);
        roleService.update(allRole, disabled);
        assertThat(permissionService.loadPermissions(userId)).doesNotContain(RoleDataScope.ALL_AUTHORITY);

        assertThat(permissionService.loadPermissions(adminId())).contains(RoleDataScope.ALL_AUTHORITY);
    }

    /**
     * 规则（用户 11-21 定，V49）：本人范围的角色勾了"审核不受数据范围限制"，多给 data:audit-any（不给 data:all）；
     * 角色改成"全部"范围时存 false，以后改回"本人"不会悄悄带着它
     */
    @Test
    void auditAnyScope_addsAuthorityAndClearedForAllScope() {
        RoleSaveReq req = roleReq("it_" + UUID.randomUUID().toString().substring(0, 8), RoleDataScope.SELF);
        req.setAuditAnyScope(true);
        Long id = roleService.create(req);
        grant(id);
        assertThat(permissionService.loadPermissions(userId))
                .contains(RoleDataScope.AUDIT_ANY_AUTHORITY).doesNotContain(RoleDataScope.ALL_AUTHORITY);

        req.setDataScope(RoleDataScope.ALL);
        roleService.update(id, req);
        assertThat(roleService.getById(id).getAuditAnyScope()).isFalse();
        assertThat(permissionService.loadPermissions(userId)).doesNotContain(RoleDataScope.AUDIT_ANY_AUTHORITY);
    }

    private SysMenu menu(Long parentId, int type, String perms) {
        SysMenu menu = new SysMenu();
        menu.setParentId(parentId);
        menu.setName("测试" + tag);
        menu.setType(type);
        menu.setPath(type == SysMenu.TYPE_BUTTON ? null : "it-" + tag);
        menu.setComponent(type == SysMenu.TYPE_MENU ? "it/" + tag + "/index" : null);
        menu.setPerms(perms);
        sysMenuMapper.insert(menu);
        return menu;
    }

    private void disable(SysMenu menu) {
        sysMenuMapper.update(new SysMenu(), new LambdaUpdateWrapper<SysMenu>()
                .set(SysMenu::getStatus, 0)
                .eq(SysMenu::getId, menu.getId()));
    }

    /** 建一个角色，分配这些菜单 */
    private Long role(RoleDataScope scope, SysMenu... menus) {
        Long id = roleService.create(roleReq("it_" + UUID.randomUUID().toString().substring(0, 8), scope));
        roleService.assignMenus(id, Arrays.stream(menus).map(SysMenu::getId).toList());
        return id;
    }

    private static RoleSaveReq roleReq(String code, RoleDataScope scope) {
        RoleSaveReq req = new RoleSaveReq();
        req.setCode(code);
        req.setName("测试角色");
        req.setDataScope(scope);
        return req;
    }

    private Long user() {
        SysUser user = new SysUser();
        user.setUsername("it_" + tag);
        user.setPassword("不用登录，不是真的哈希");
        user.setNickname("测试");
        user.setStatus(1);
        sysUserMapper.insert(user);
        return user.getId();
    }

    private void grant(Long roleId) {
        sysUserRoleMapper.insert(new SysUserRole(userId, roleId));
    }

    private Long adminId() {
        return sysUserMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, "admin")).getId();
    }

    private List<Long> sidebarIds() {
        return sysMenuMapper.selectMenusByUserId(userId).stream().map(SysMenu::getId).toList();
    }
}
