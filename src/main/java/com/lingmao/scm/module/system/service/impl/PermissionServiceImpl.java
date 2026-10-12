package com.lingmao.scm.module.system.service.impl;

import com.lingmao.scm.module.system.dto.MenuVO;
import com.lingmao.scm.module.system.entity.SysUser;
import com.lingmao.scm.module.system.mapper.SysMenuMapper;
import com.lingmao.scm.module.system.mapper.SysRoleMapper;
import com.lingmao.scm.module.system.mapper.SysUserMapper;
import com.lingmao.scm.module.system.service.PermissionService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class PermissionServiceImpl implements PermissionService {

    private final SysUserMapper sysUserMapper;
    private final SysRoleMapper sysRoleMapper;
    private final SysMenuMapper sysMenuMapper;

    @Override
    public Set<String> loadPermissions(Long userId) {
        SysUser user = sysUserMapper.selectById(userId);
        if (user == null || user.getStatus() != 1) {
            return null;
        }
        return new LinkedHashSet<>(sysMenuMapper.selectPermsByUserId(userId));
    }

    @Override
    public List<String> loadRoleCodes(Long userId) {
        return sysRoleMapper.selectCodesByUserId(userId);
    }

    @Override
    public List<MenuVO> loadMenuTree(Long userId) {
        return MenuVO.tree(sysMenuMapper.selectMenusByUserId(userId));
    }
}
