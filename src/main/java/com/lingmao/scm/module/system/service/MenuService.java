package com.lingmao.scm.module.system.service;

import com.lingmao.scm.module.system.dto.MenuSaveReq;
import com.lingmao.scm.module.system.dto.MenuVO;
import com.lingmao.scm.module.system.entity.SysMenu;

import java.util.List;

/** 菜单管理：整棵树一次查出来，前端自己展开；增删改都要守住"树"的规矩 */
public interface MenuService {

    /** 全部菜单（含按钮、含停用的）组成的树，菜单管理页和给角色分配菜单时用 */
    List<MenuVO> tree();

    SysMenu getById(Long id);

    Long create(MenuSaveReq req);

    /** 修改。上级不能是自己，也不能是自己的下级（否则树会变成环） */
    void update(Long id, MenuSaveReq req);

    /** 删除。有下级的不能删；删掉以后，各角色和它的关联一起清掉 */
    void delete(Long id);
}
