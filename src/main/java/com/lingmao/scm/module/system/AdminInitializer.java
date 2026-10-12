package com.lingmao.scm.module.system;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lingmao.scm.module.system.entity.SysRole;
import com.lingmao.scm.module.system.entity.SysUser;
import com.lingmao.scm.module.system.entity.SysUserRole;
import com.lingmao.scm.module.system.mapper.SysRoleMapper;
import com.lingmao.scm.module.system.mapper.SysUserMapper;
import com.lingmao.scm.module.system.mapper.SysUserRoleMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 启动时保证有一个能登录、能管理一切的 admin：
 * 1. 库里没有 admin 用户 → 用配置的初始密码建一个
 * 2. admin 还没有超级管理员角色 → 补上（V5 建角色时 admin 用户可能还不存在，比如全新的生产库）
 * 两步都是"先查再做"，重复启动不会重复插入
 */
@Slf4j
@Component
public class AdminInitializer implements ApplicationRunner {

    private final SysUserMapper sysUserMapper;
    private final SysRoleMapper sysRoleMapper;
    private final SysUserRoleMapper sysUserRoleMapper;
    private final PasswordEncoder passwordEncoder;
    private final String initPassword;

    public AdminInitializer(SysUserMapper sysUserMapper,
                            SysRoleMapper sysRoleMapper,
                            SysUserRoleMapper sysUserRoleMapper,
                            PasswordEncoder passwordEncoder,
                            @Value("${lingmao.init-admin-password}") String initPassword) {
        this.sysUserMapper = sysUserMapper;
        this.sysRoleMapper = sysRoleMapper;
        this.sysUserRoleMapper = sysUserRoleMapper;
        this.passwordEncoder = passwordEncoder;
        this.initPassword = initPassword;
    }

    @Override
    public void run(ApplicationArguments args) {
        SysUser admin = sysUserMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, "admin"));
        if (admin == null) {
            if (!StringUtils.hasText(initPassword)) {
                log.warn("库里还没有 admin，也没配置 INIT_ADMIN_PASSWORD，跳过创建");
                return;
            }
            admin = new SysUser();
            admin.setUsername("admin");
            admin.setPassword(passwordEncoder.encode(initPassword));
            admin.setNickname("管理员");
            sysUserMapper.insert(admin);
            log.info("已创建初始管理员 admin，id = {}", admin.getId());
        }
        ensureAdminRole(admin.getId());
    }

    private void ensureAdminRole(Long adminId) {
        SysRole role = sysRoleMapper.selectOne(new LambdaQueryWrapper<SysRole>()
                .eq(SysRole::getCode, SysRole.ADMIN_CODE));
        if (role == null) {
            log.warn("没有找到超级管理员角色（code = admin），跳过分配");
            return;
        }
        boolean assigned = sysUserRoleMapper.exists(new LambdaQueryWrapper<SysUserRole>()
                .eq(SysUserRole::getUserId, adminId)
                .eq(SysUserRole::getRoleId, role.getId()));
        if (!assigned) {
            sysUserRoleMapper.insert(new SysUserRole(adminId, role.getId()));
            log.info("已给 admin 分配超级管理员角色");
        }
    }
}
