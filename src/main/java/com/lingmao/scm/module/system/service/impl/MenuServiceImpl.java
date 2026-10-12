package com.lingmao.scm.module.system.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.module.system.dto.MenuSaveReq;
import com.lingmao.scm.module.system.dto.MenuVO;
import com.lingmao.scm.module.system.entity.SysMenu;
import com.lingmao.scm.module.system.entity.SysRoleMenu;
import com.lingmao.scm.module.system.mapper.SysMenuMapper;
import com.lingmao.scm.module.system.mapper.SysRoleMenuMapper;
import com.lingmao.scm.module.system.service.MenuService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class MenuServiceImpl implements MenuService {

    private final SysMenuMapper sysMenuMapper;
    private final SysRoleMenuMapper sysRoleMenuMapper;

    @Override
    public List<MenuVO> tree() {
        List<SysMenu> all = sysMenuMapper.selectList(new LambdaQueryWrapper<SysMenu>()
                .orderByAsc(SysMenu::getSort)
                .orderByAsc(SysMenu::getId));
        return MenuVO.tree(all);
    }

    @Override
    public SysMenu getById(Long id) {
        SysMenu menu = sysMenuMapper.selectById(id);
        if (menu == null) {
            throw new BizException(404, "菜单不存在: " + id);
        }
        return menu;
    }

    @Override
    @Transactional
    public Long create(MenuSaveReq req) {
        checkFields(req);
        ensureParentExists(req.getParentId());
        SysMenu menu = new SysMenu();
        BeanUtils.copyProperties(req, menu);
        sysMenuMapper.insert(menu);
        return menu.getId();
    }

    @Override
    @Transactional
    public void update(Long id, MenuSaveReq req) {
        SysMenu menu = getById(id);
        checkFields(req);
        ensureParentExists(req.getParentId());
        ensureNotOwnDescendant(id, req.getParentId());
        BeanUtils.copyProperties(req, menu);
        sysMenuMapper.updateById(menu);
    }

    @Override
    @Transactional
    public void delete(Long id) {
        getById(id);
        boolean hasChildren = sysMenuMapper.exists(new LambdaQueryWrapper<SysMenu>()
                .eq(SysMenu::getParentId, id));
        if (hasChildren) {
            throw new BizException("有下级菜单，不能删除");
        }
        sysMenuMapper.deleteById(id);
        sysRoleMenuMapper.delete(new LambdaQueryWrapper<SysRoleMenu>()
                .eq(SysRoleMenu::getMenuId, id));
    }

    /** 不同类型要填的字段不同：目录、菜单要有路由地址，菜单还要有组件，按钮要有权限标识 */
    private void checkFields(MenuSaveReq req) {
        int type = req.getType();
        if (type != SysMenu.TYPE_BUTTON && !StringUtils.hasText(req.getPath())) {
            throw new BizException("目录和菜单必须填路由地址");
        }
        if (type == SysMenu.TYPE_MENU && !StringUtils.hasText(req.getComponent())) {
            throw new BizException("菜单必须填组件路径");
        }
        if (type == SysMenu.TYPE_BUTTON && !StringUtils.hasText(req.getPerms())) {
            throw new BizException("按钮必须填权限标识");
        }
    }

    private void ensureParentExists(Long parentId) {
        if (parentId != 0 && sysMenuMapper.selectById(parentId) == null) {
            throw new BizException("上级菜单不存在: " + parentId);
        }
    }

    /** 从新上级开始一路往上找，找到了自己，说明新上级是自己或自己的下级 */
    private void ensureNotOwnDescendant(Long id, Long newParentId) {
        Long current = newParentId;
        while (current != 0) {
            if (Objects.equals(current, id)) {
                throw new BizException("上级菜单不能是自己或自己的下级");
            }
            current = sysMenuMapper.selectById(current).getParentId();
        }
    }
}
