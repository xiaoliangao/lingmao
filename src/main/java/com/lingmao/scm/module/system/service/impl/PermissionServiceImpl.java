package com.lingmao.scm.module.system.service.impl;

import com.lingmao.scm.module.system.dto.MenuVO;
import com.lingmao.scm.module.system.entity.RoleDataScope;
import com.lingmao.scm.module.system.entity.SysUser;
import com.lingmao.scm.module.system.mapper.SysMenuMapper;
import com.lingmao.scm.module.system.mapper.SysRoleMapper;
import com.lingmao.scm.module.system.mapper.SysUserMapper;
import com.lingmao.scm.module.system.service.PermissionService;
import com.lingmao.scm.security.LineScope;
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
        Set<String> perms = new LinkedHashSet<>(sysMenuMapper.selectPermsByUserId(userId));
        // 数据范围是"全部"的多给一个 data:all（RFC 001 §8）：过滤器每个请求都调这里，以后查单据时看权限就知道，不用另外查库。
        // 品类线同理（RFC 013 §2）：超级管理员 line:all，别人每条线一个 line:XXX
        SysRoleMapper.ScopeFlags flags = sysRoleMapper.selectScopeFlags(userId);
        if (flags != null && flags.allData()) {
            perms.add(RoleDataScope.ALL_AUTHORITY);
        }
        // 审核不受数据范围限制（V49）：同理多给一个 data:audit-any，DataScope.orAuditing 看它
        if (flags != null && flags.auditAny()) {
            perms.add(RoleDataScope.AUDIT_ANY_AUTHORITY);
        }
        if (flags != null && flags.admin()) {
            perms.add(LineScope.ALL_AUTHORITY);
        } else if (user.getProductLines() != null) {
            perms.addAll(LineScope.authorities(user.getProductLines()));
        }
        return perms;
    }

    @Override
    public List<String> loadRoleCodes(Long userId) {
        return sysRoleMapper.selectCodesByUserId(userId);
    }

    @Override
    public List<Long> loadRoleIds(Long userId) {
        return sysRoleMapper.selectIdsByUserId(userId);
    }

    @Override
    public boolean roleCanApprove(Long roleId, String permission) {
        return sysMenuMapper.roleCanApprove(roleId, permission);
    }

    @Override
    public List<MenuVO> loadMenuTree(Long userId) {
        return MenuVO.tree(sysMenuMapper.selectMenusByUserId(userId));
    }
}
