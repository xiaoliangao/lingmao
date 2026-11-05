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
import com.lingmao.scm.security.LoginFailCounter;
import com.lingmao.scm.security.LoginSessionStore;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private final SysUserMapper sysUserMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final LoginSessionStore loginSessionStore;
    private final PermissionService permissionService;
    private final LoginFailCounter loginFailCounter;

    @Override
    public String login(LoginReq req) {
        String username = req.getUsername();
        // 锁住了就不再比对密码：不然锁定期间照样能一直试，锁就白加了
        if (loginFailCounter.isLocked(username)) {
            throw new BizException("密码输错次数太多，账号已锁定，请 " + loginFailCounter.minutesLeft(username) + " 分钟后再试");
        }
        SysUser user = sysUserMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, username));
        if (user == null || !passwordEncoder.matches(req.getPassword(), user.getPassword())) {
            // 用户名不存在也照样计数、照样提示还能试几次：提示不一样的话，别人就能试出哪些用户名是真的
            int left = loginFailCounter.recordFailure(username);
            throw new BizException(left > 0
                    ? "用户名或密码错误，还能试 " + left + " 次"
                    : "用户名或密码错误，账号已锁定，请 " + loginFailCounter.minutesLeft(username) + " 分钟后再试");
        }
        if (user.getStatus() != 1) {
            throw new BizException("账号已停用");
        }
        loginFailCounter.clear(username);
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

    @Override
    @Transactional
    public void changePassword(Long userId, String oldPassword, String newPassword) {
        SysUser user = sysUserMapper.selectById(userId);
        if (user == null) {
            throw new BizException(404, "用户不存在: " + userId);
        }
        if (!passwordEncoder.matches(oldPassword, user.getPassword())) {
            throw new BizException("旧密码不对");
        }
        user.setPassword(passwordEncoder.encode(newPassword));
        sysUserMapper.updateById(user);
    }
}
