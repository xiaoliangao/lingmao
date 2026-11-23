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
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 只为本次原子创建的 admin 分配初始角色。已有账号（包括停用、软删除和已撤权）完全不动。
 * 用户和角色关联在同一个事务里，任何一步失败都不留下半初始化账号。
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
    @Transactional
    public void run(ApplicationArguments args) {
        if (!StringUtils.hasText(initPassword)) {
            log.debug("未配置 INIT_ADMIN_PASSWORD，跳过初始管理员创建");
            return;
        }
        SysUser admin = new SysUser();
        admin.setPassword(passwordEncoder.encode(initPassword));
        admin.setNickname("管理员");
        sysRoleMapper.lockAuthorizationGraph();
        if (sysUserMapper.insertInitialAdmin(admin) == 0) return;

        // 授权前锁住本次使用的启用角色，避免和角色停用/删除交错。
        SysRole role = sysRoleMapper.selectOne(new LambdaQueryWrapper<SysRole>()
                .eq(SysRole::getCode, SysRole.ADMIN_CODE).eq(SysRole::getStatus, 1).last("FOR SHARE"));
        if (role == null) {
            throw new IllegalStateException("没有启用的超级管理员角色，初始管理员创建已回滚");
        }
        if (sysUserRoleMapper.insert(new SysUserRole(admin.getId(), role.getId())) != 1) {
            throw new IllegalStateException("初始管理员角色分配失败");
        }
    }
}
