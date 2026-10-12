package com.lingmao.scm.module.system.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.common.page.PageResult;
import com.lingmao.scm.module.system.dto.RoleQuery;
import com.lingmao.scm.module.system.dto.RoleSaveReq;
import com.lingmao.scm.module.system.entity.SysMenu;
import com.lingmao.scm.module.system.entity.SysRole;
import com.lingmao.scm.module.system.entity.SysRoleMenu;
import com.lingmao.scm.module.system.entity.SysUserRole;
import com.lingmao.scm.module.system.mapper.SysMenuMapper;
import com.lingmao.scm.module.system.mapper.SysRoleMapper;
import com.lingmao.scm.module.system.mapper.SysRoleMenuMapper;
import com.lingmao.scm.module.system.mapper.SysUserRoleMapper;
import com.lingmao.scm.module.system.service.RoleService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;

@Service
@RequiredArgsConstructor
public class RoleServiceImpl implements RoleService {

    private final SysRoleMapper sysRoleMapper;
    private final SysRoleMenuMapper sysRoleMenuMapper;
    private final SysUserRoleMapper sysUserRoleMapper;
    private final SysMenuMapper sysMenuMapper;

    @Override
    public PageResult<SysRole> page(RoleQuery query) {
        LambdaQueryWrapper<SysRole> wrapper = new LambdaQueryWrapper<SysRole>()
                .like(StringUtils.hasText(query.getCode()), SysRole::getCode, query.getCode())
                .like(StringUtils.hasText(query.getName()), SysRole::getName, query.getName())
                .orderByAsc(SysRole::getSort)
                .orderByAsc(SysRole::getId);
        return PageResult.of(sysRoleMapper.selectPage(query.toPage(), wrapper));
    }

    @Override
    public List<SysRole> listEnabled() {
        return sysRoleMapper.selectList(new LambdaQueryWrapper<SysRole>()
                .eq(SysRole::getStatus, 1)
                .orderByAsc(SysRole::getSort)
                .orderByAsc(SysRole::getId));
    }

    @Override
    public SysRole getById(Long id) {
        SysRole role = sysRoleMapper.selectById(id);
        if (role == null) {
            throw new BizException(404, "角色不存在: " + id);
        }
        return role;
    }

    @Override
    @Transactional
    public Long create(RoleSaveReq req) {
        ensureCodeUnique(req.getCode(), null);
        SysRole role = new SysRole();
        BeanUtils.copyProperties(req, role);
        sysRoleMapper.insert(role);
        return role.getId();
    }

    @Override
    @Transactional
    public void update(Long id, RoleSaveReq req) {
        SysRole role = getById(id);
        ensureNotAdmin(role);
        ensureCodeUnique(req.getCode(), id);
        BeanUtils.copyProperties(req, role);
        sysRoleMapper.updateById(role);
    }

    @Override
    @Transactional
    public void delete(Long id) {
        SysRole role = getById(id);
        ensureNotAdmin(role);
        boolean assigned = sysUserRoleMapper.exists(new LambdaQueryWrapper<SysUserRole>()
                .eq(SysUserRole::getRoleId, id));
        if (assigned) {
            throw new BizException("角色已分配给用户，不能删除");
        }
        sysRoleMapper.deleteById(id);
        sysRoleMenuMapper.delete(new LambdaQueryWrapper<SysRoleMenu>()
                .eq(SysRoleMenu::getRoleId, id));
    }

    @Override
    public List<Long> getMenuIds(Long id) {
        getById(id);
        return sysRoleMenuMapper.selectList(new LambdaQueryWrapper<SysRoleMenu>()
                        .eq(SysRoleMenu::getRoleId, id))
                .stream().map(SysRoleMenu::getMenuId).toList();
    }

    @Override
    @Transactional
    public void assignMenus(Long id, List<Long> menuIds) {
        SysRole role = getById(id);
        ensureNotAdmin(role);
        List<Long> distinctIds = menuIds.stream().distinct().toList();
        if (!distinctIds.isEmpty()) {
            Long found = sysMenuMapper.selectCount(new LambdaQueryWrapper<SysMenu>()
                    .in(SysMenu::getId, distinctIds));
            if (found != distinctIds.size()) {
                throw new BizException("有菜单不存在或已删除");
            }
            boolean hasAdminOnly = sysMenuMapper.exists(new LambdaQueryWrapper<SysMenu>()
                    .in(SysMenu::getId, distinctIds)
                    .eq(SysMenu::getAdminOnly, true));
            if (hasAdminOnly) {
                throw new BizException("管理后台的菜单只给超级管理员，不能分配给角色");
            }
        }
        sysRoleMenuMapper.delete(new LambdaQueryWrapper<SysRoleMenu>()
                .eq(SysRoleMenu::getRoleId, id));
        if (!distinctIds.isEmpty()) {
            sysRoleMenuMapper.insert(distinctIds.stream().map(menuId -> new SysRoleMenu(id, menuId)).toList());
        }
    }

    private void ensureNotAdmin(SysRole role) {
        if (SysRole.ADMIN_CODE.equals(role.getCode())) {
            throw new BizException("超级管理员角色不能修改、删除或分配菜单");
        }
    }

    private void ensureCodeUnique(String code, Long excludeId) {
        boolean exists = sysRoleMapper.exists(new LambdaQueryWrapper<SysRole>()
                .eq(SysRole::getCode, code)
                .ne(excludeId != null, SysRole::getId, excludeId));
        if (exists) {
            throw new BizException("角色编码已存在: " + code);
        }
    }
}
