package com.lingmao.scm.module.system;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lingmao.scm.module.system.entity.SysUser;
import com.lingmao.scm.module.system.mapper.SysUserMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Slf4j
@Component
public class AdminInitializer implements ApplicationRunner {

    private final SysUserMapper sysUserMapper;
    private final PasswordEncoder passwordEncoder;
    private final String initPassword;

    public AdminInitializer(SysUserMapper sysUserMapper,
                            PasswordEncoder passwordEncoder,
                            @Value("${lingmao.init-admin-password}") String initPassword) {
        this.sysUserMapper = sysUserMapper;
        this.passwordEncoder = passwordEncoder;
        this.initPassword = initPassword;
    }

    @Override
    public void run(ApplicationArguments args) {
        boolean exists = sysUserMapper.exists(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, "admin"));
        if (exists) {
            return;
        }
        if (!StringUtils.hasText(initPassword)) {
            log.warn("库里还没有 admin，也没配置 INIT_ADMIN_PASSWORD，跳过创建");
            return;
        }
        SysUser admin = new SysUser();
        admin.setUsername("admin");
        admin.setPassword(passwordEncoder.encode(initPassword));
        admin.setNickname("管理员");
        sysUserMapper.insert(admin);
        log.info("已创建初始管理员 admin，id = {}", admin.getId());
    }
}