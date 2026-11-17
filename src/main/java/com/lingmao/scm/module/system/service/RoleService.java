package com.lingmao.scm.module.system.service;

import com.lingmao.scm.common.page.PageResult;
import com.lingmao.scm.module.system.dto.RoleQuery;
import com.lingmao.scm.module.system.dto.RoleSaveReq;
import com.lingmao.scm.module.system.entity.SysRole;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/** 角色管理。超级管理员角色（admin）拥有全部权限，不能修改、删除、分配菜单 */
public interface RoleService {

    PageResult<SysRole> page(RoleQuery query);

    /** 全部启用的角色，给用户分配角色时做下拉选项 */
    List<SysRole> listEnabled();

    SysRole getById(Long id);

    /**
     * 一批角色的名字，id → 名字：审批规则、审批块上显示"哪个角色审"。删除了的也给（规则、审过的级上照样要看得出是谁）；
     * 查不到的 id 不在 map 里。别的域显示角色名调这个，不 JOIN sys_role
     */
    Map<Long, String> names(Collection<Long> ids);

    /** 角色在不在、启用没有（删除了的算不在）：审批规则选审批角色、提交时检查规则用 */
    boolean isEnabled(long id);

    Long create(RoleSaveReq req);

    void update(Long id, RoleSaveReq req);

    /** 删除。已经分配给用户的角色不能删 */
    void delete(Long id);

    /** 这个角色分配到的菜单 id（含目录、菜单、按钮） */
    List<Long> getMenuIds(Long id);

    /** 重新分配菜单：先清空，再按 menuIds 写入 */
    void assignMenus(Long id, List<Long> menuIds);
}
