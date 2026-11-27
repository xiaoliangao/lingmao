package com.lingmao.scm.module.system.service.impl;

import com.lingmao.scm.module.system.mapper.ConfigMapper;
import com.lingmao.scm.module.system.service.SysConfigService;
import com.lingmao.scm.security.LoginUser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SysConfigServiceImpl implements SysConfigService {

    private final ConfigMapper configMapper;

    @Override
    public String get(String key) {
        return configMapper.selectValue(key);
    }

    @Override
    @Transactional
    public void put(String key, String json) {
        configMapper.upsert(key, json, LoginUser.currentUserId());
    }
}
