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
 * 全新的库（从来没有过 admin 账号）启动时，用配置的初始密码建 admin，给超级管理员角色（V5 建角色时 admin 还不存在）。
 * <p>
 * 只管"第一次"：admin 已经有了（停用的、删了的也算）就什么都不动。超级管理员把 admin 停用、删掉、去掉角色
 * （比如交给甲方的人以后不用这个公共账号了），重启不能让它自己回来；原来每次启动都给 admin 补角色、
 * admin 删了还会用 .env 里的初始密码再建一个。建账号和给角色在一个事务里，不会只建了人没给角色
 */
@Slf4j
@Component
public class AdminInitializer implements ApplicationRunner {

    static final String USERNAME = "admin";

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
        if (sysUserMapper.existsEverByUsername(USERNAME)) {
            return;
        }
        if (!StringUtils.hasText(initPassword)) {
            log.warn("库里还没有 admin，也没配置 INIT_ADMIN_PASSWORD，跳过创建");
            return;
        }
        SysRole role = sysRoleMapper.selectOne(new LambdaQueryWrapper<SysRole>()
                .eq(SysRole::getCode, SysRole.ADMIN_CODE));
        if (role == null) {
            // V5 预置的角色被删了：建出来的 admin 什么都做不了，不如不建，日志里说清楚
            log.warn("没有找到超级管理员角色（code = admin），不建 admin");
            return;
        }
        SysUser admin = new SysUser();
        admin.setUsername(USERNAME);
        admin.setPassword(passwordEncoder.encode(initPassword));
        admin.setNickname("管理员");
        sysUserMapper.insert(admin);
        sysUserRoleMapper.insert(new SysUserRole(admin.getId(), role.getId()));
        log.info("已创建初始管理员 admin（超级管理员），id = {}", admin.getId());
    }
}
