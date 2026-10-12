package com.lingmao.scm.module.system.service;

import com.lingmao.scm.common.page.PageResult;
import com.lingmao.scm.module.system.dto.RoleQuery;
import com.lingmao.scm.module.system.dto.RoleSaveReq;
import com.lingmao.scm.module.system.entity.SysRole;

import java.util.List;

/** 角色管理。超级管理员角色（admin）拥有全部权限，不能修改、删除、分配菜单 */
public interface RoleService {

    PageResult<SysRole> page(RoleQuery query);

    /** 全部启用的角色，给用户分配角色时做下拉选项 */
    List<SysRole> listEnabled();

    SysRole getById(Long id);

    Long create(RoleSaveReq req);

    void update(Long id, RoleSaveReq req);

    /** 删除。已经分配给用户的角色不能删 */
    void delete(Long id);

    /** 这个角色分配到的菜单 id（含目录、菜单、按钮） */
    List<Long> getMenuIds(Long id);

    /** 重新分配菜单：先清空，再按 menuIds 写入 */
    void assignMenus(Long id, List<Long> menuIds);
}
