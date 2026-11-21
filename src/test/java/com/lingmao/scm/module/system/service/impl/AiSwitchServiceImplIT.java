package com.lingmao.scm.module.system.service.impl;

import com.lingmao.scm.IntegrationTest;
import com.lingmao.scm.module.system.dto.AiSwitchVO;
import com.lingmao.scm.module.system.service.AiSwitchService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** AI 功能统一开关在真库上（RFC 017 §3.7）：V52 的默认值和菜单、切换立刻生效、删掉这一行等于关 */
@IntegrationTest
class AiSwitchServiceImplIT {

    /** 审计人没有外键，随便一个 id */
    private static final long ADMIN = 900101L;

    @Autowired
    private AiSwitchService aiSwitchService;

    @Autowired
    private JdbcTemplate jdbc;

    private Map<String, Object> original;

    @BeforeEach
    void remember() {
        original = jdbc.queryForList("SELECT config_value::text AS v, remark FROM sys_config WHERE config_key = 'ai.switch'")
                .stream().findFirst().orElse(null);
    }

    /** 测完放回原样：别的测试看到的还是 V52 装好的那一行 */
    @AfterEach
    void restore() {
        jdbc.update("DELETE FROM sys_config WHERE config_key = 'ai.switch'");
        if (original != null) {
            jdbc.update("INSERT INTO sys_config (config_key, config_value, remark) VALUES ('ai.switch', CAST(? AS jsonb), ?)",
                    original.get("v"), original.get("remark"));
        }
    }

    /** 规则：装好是关的；菜单"AI 功能"挂在管理后台，只给超级管理员 */
    @Test
    void installedOff_menuAdminOnly() {
        assertThat(aiSwitchService.enabled()).isFalse();
        assertThat(aiSwitchService.get()).isEqualTo(new AiSwitchVO(false, null, null));
        assertThat(jdbc.queryForObject("""
                SELECT m.admin_only FROM sys_menu m JOIN sys_menu d ON d.id = m.parent_id AND d.path = '/system'
                WHERE m.perms = 'system:ai:switch' AND m.deleted = false""", Boolean.class)).isTrue();
    }

    /** 规则：打开、关闭下一次读就变（不缓存）；记下谁、什么时候改的；值里别的键留着 */
    @Test
    void switchTakesEffectImmediately() {
        jdbc.update("UPDATE sys_config SET config_value = '{\"enabled\": false, \"features\": {\"x\": true}}' WHERE config_key = 'ai.switch'");

        AiSwitchVO on = aiSwitchService.set(true, ADMIN);
        assertThat(on.enabled()).isTrue();
        assertThat(on.updatedAt()).isNotNull();
        assertThat(aiSwitchService.enabled()).isTrue();
        assertThat(jdbc.queryForObject("SELECT config_value->'features'->>'x' FROM sys_config WHERE config_key = 'ai.switch'", String.class))
                .isEqualTo("true");
        assertThat(jdbc.queryForObject("SELECT update_by FROM sys_config WHERE config_key = 'ai.switch'", Long.class)).isEqualTo(ADMIN);

        aiSwitchService.set(false, ADMIN);
        assertThat(aiSwitchService.enabled()).isFalse();
    }

    /** 规则：开着的时候有人把这一行删了，马上按关；再开会把这一行建回来 */
    @Test
    void rowDeleted_meansOff() {
        aiSwitchService.set(true, ADMIN);
        jdbc.update("DELETE FROM sys_config WHERE config_key = 'ai.switch'");

        assertThat(aiSwitchService.enabled()).isFalse();
        assertThat(aiSwitchService.get().enabled()).isFalse();

        aiSwitchService.set(true, ADMIN);
        assertThat(aiSwitchService.enabled()).isTrue();
    }
}
