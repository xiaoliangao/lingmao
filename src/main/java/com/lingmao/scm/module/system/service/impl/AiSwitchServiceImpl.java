package com.lingmao.scm.module.system.service.impl;

import com.lingmao.scm.common.log.OperLogDetail;
import com.lingmao.scm.module.system.dto.AiSwitchVO;
import com.lingmao.scm.module.system.entity.SysUser;
import com.lingmao.scm.module.system.mapper.ConfigMapper;
import com.lingmao.scm.module.system.mapper.SysUserMapper;
import com.lingmao.scm.module.system.service.AiSwitchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiSwitchServiceImpl implements AiSwitchService {

    static final String KEY = "ai.switch";

    private final ConfigMapper configMapper;
    private final SysUserMapper sysUserMapper;
    private final JsonMapper jsonMapper;

    @Override
    public boolean enabled() {
        try {
            return enabledIn(configMapper.selectValue(KEY));
        } catch (RuntimeException e) {
            // 读不到就当关：这个开关是拿来"关掉"的，出错时不能变成开着
            log.warn("读 AI 功能开关出错，按关处理", e);
            return false;
        }
    }

    @Override
    public AiSwitchVO get() {
        // 设置页读出错就报错，不按关：页面上显示"关"会让人以为已经关了（拦功能的 enabled() 才按关）
        ConfigMapper.Row row = configMapper.selectRow(KEY);
        if (row == null) {
            return new AiSwitchVO(false, null, null);
        }
        return new AiSwitchVO(enabledIn(row.getConfigValue()), userName(row.getUpdateBy()), row.getUpdateBy() == null ? null : row.getUpdateTime());
    }

    @Override
    @Transactional
    public AiSwitchVO set(boolean enabled, Long userId) {
        // 锁住这一行再读：两个人同时切，后来的读到前一个改完的值，日志里的"改前"才对，值里别的键也不会被旧值盖掉
        configMapper.ensure(KEY);
        String old = configMapper.selectValueForUpdate(KEY);
        boolean before = enabledIn(old);
        // 值里别的键（以后各档的分开关）原样留着，只改总开关
        ObjectNode value = parseObject(old);
        value.put("enabled", enabled);
        configMapper.upsert(KEY, jsonMapper.writeValueAsString(value), userId);
        AiSwitchVO result = get();
        OperLogDetail.put("改前", before ? "开" : "关");
        OperLogDetail.put("改后", enabled ? "开" : "关");
        return result;
    }

    private boolean enabledIn(String json) {
        if (!StringUtils.hasText(json)) {
            return false;
        }
        try {
            JsonNode flag = jsonMapper.readTree(json).get("enabled");
            return flag != null && flag.isBoolean() && flag.booleanValue();
        } catch (RuntimeException e) {
            log.warn("AI 功能开关的值不是 JSON，按关处理: {}", json);
            return false;
        }
    }

    private ObjectNode parseObject(String json) {
        if (StringUtils.hasText(json)) {
            try {
                if (jsonMapper.readTree(json) instanceof ObjectNode object) {
                    return object;
                }
            } catch (RuntimeException e) {
                log.warn("AI 功能开关原来的值不是 JSON，整个换掉: {}", json);
            }
        }
        return jsonMapper.createObjectNode();
    }

    private String userName(Long userId) {
        if (userId == null) {
            return null;
        }
        // 删了的人也要取到名字（selectById 会滤掉逻辑删除的）
        List<SysUser> users = sysUserMapper.selectNamesByIds(List.of(userId));
        if (users.isEmpty()) {
            return null;
        }
        SysUser user = users.getFirst();
        return StringUtils.hasText(user.getNickname()) ? user.getNickname() : user.getUsername();
    }
}
