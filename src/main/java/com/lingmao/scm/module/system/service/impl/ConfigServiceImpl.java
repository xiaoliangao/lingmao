package com.lingmao.scm.module.system.service.impl;

import com.lingmao.scm.module.system.mapper.ConfigMapper;
import com.lingmao.scm.module.system.service.ConfigService;
import com.lingmao.scm.security.LoginUser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.util.Optional;

@Service
@RequiredArgsConstructor
public class ConfigServiceImpl implements ConfigService {

    private final ConfigMapper configMapper;
    private final JsonMapper jsonMapper;

    @Override
    public <T> Optional<T> get(String key, Class<T> type) {
        String json = configMapper.selectValue(key);
        return json == null ? Optional.empty() : Optional.ofNullable(jsonMapper.readValue(json, type));
    }

    @Override
    public void put(String key, Object value) {
        configMapper.upsert(key, jsonMapper.writeValueAsString(value), LoginUser.currentUserId());
    }
}
