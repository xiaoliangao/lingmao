package com.lingmao.scm.module.system.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.module.system.dto.LoginReq;
import com.lingmao.scm.module.system.dto.UserInfoVO;
import com.lingmao.scm.module.system.entity.SysUser;
import com.lingmao.scm.module.system.mapper.SysUserMapper;
import com.lingmao.scm.module.system.service.AuthService;
import com.lingmao.scm.module.system.service.PermissionService;
import com.lingmao.scm.security.JwtUtil;
import com.lingmao.scm.security.LoginSessionStore;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private final SysUserMapper sysUserMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final LoginSessionStore loginSessionStore;
    private final PermissionService permissionService;

    @Override
    public String login(LoginReq req) {
        SysUser user = sysUserMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, req.getUsername()));
        if (user == null || !passwordEncoder.matches(req.getPassword(), user.getPassword())) {
            throw new BizException("用户名或密码错误");
        }
        if (user.getStatus() != 1) {
            throw new BizException("账号已停用");
        }
        String jti = UUID.randomUUID().toString();
        loginSessionStore.save(jti, user.getId());
        return jwtUtil.createToken(user.getId(), jti);
    }

    @Override
    public UserInfoVO me(Long userId) {
        SysUser user = sysUserMapper.selectById(userId);
        if (user == null) {
            throw new BizException(404, "用户不存在: " + userId);
        }
        return new UserInfoVO(user.getId(), user.getUsername(), user.getNickname(),
                permissionService.loadRoleCodes(userId),
                permissionService.loadPermissions(userId),
                permissionService.loadMenuTree(userId));
    }

    @Override
    public void logout(String jti) {
        loginSessionStore.remove(jti);
    }
}