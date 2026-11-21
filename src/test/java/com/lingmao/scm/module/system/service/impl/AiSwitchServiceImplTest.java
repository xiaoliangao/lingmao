package com.lingmao.scm.module.system.service.impl;

import com.lingmao.scm.common.log.OperLogDetail;
import com.lingmao.scm.module.system.dto.AiSwitchVO;
import com.lingmao.scm.module.system.entity.SysUser;
import com.lingmao.scm.module.system.mapper.ConfigMapper;
import com.lingmao.scm.module.system.mapper.SysUserMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import tools.jackson.databind.json.JsonMapper;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiSwitchServiceImplTest {

    @Mock
    private ConfigMapper configMapper;

    @Mock
    private SysUserMapper sysUserMapper;

    private AiSwitchServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new AiSwitchServiceImpl(configMapper, sysUserMapper, JsonMapper.builder().build());
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    /** 规则：只有值里 enabled 是 true 才算开；没有这一行、没有 enabled、不是布尔、不是 JSON、读库出错，一律按关 */
    @Test
    void enabled_onlyExplicitTrue() {
        when(configMapper.selectValue("ai.switch")).thenReturn("{\"enabled\": true}");
        assertThat(service.enabled()).isTrue();
        for (String value : new String[]{null, "{}", "{\"enabled\": \"true\"}", "{\"enabled\": 1}", "{\"enabled\": null}",
                "[{\"enabled\": true}]", "true", "{\"a\": {\"enabled\": true}}", "不是 JSON", "{\"enabled\": false}"}) {
            when(configMapper.selectValue("ai.switch")).thenReturn(value);
            assertThat(service.enabled()).as(value).isFalse();
        }

        when(configMapper.selectValue("ai.switch")).thenThrow(new RuntimeException("数据库连不上"));
        assertThat(service.enabled()).isFalse();
    }

    /**
     * 规则：先占住、锁住这一行再读（并发切换时"改前"对、别的键不丢）；打开时只改总开关，值里别的键（以后各档的分开关）原样留着；
     * 改前、改后进这次请求的操作日志；改的人账号删了也显示名字
     */
    @Test
    void set_keepsOtherKeysAndRecordsBeforeAfter() {
        when(configMapper.selectValueForUpdate("ai.switch")).thenReturn("{\"enabled\": false, \"features\": {\"assistant\": true}}");
        ConfigMapper.Row row = new ConfigMapper.Row();
        row.setConfigValue("{\"enabled\": true, \"features\": {\"assistant\": true}}");
        row.setUpdateBy(1L);
        row.setUpdateTime(OffsetDateTime.parse("2026-11-21T15:00:00+08:00"));
        when(configMapper.selectRow("ai.switch")).thenReturn(row);
        SysUser admin = new SysUser();
        admin.setUsername("admin");
        when(sysUserMapper.selectNamesByIds(List.of(1L))).thenReturn(List.of(admin));

        AiSwitchVO result = service.set(true, 1L);

        InOrder order = inOrder(configMapper);
        order.verify(configMapper).ensure("ai.switch");
        order.verify(configMapper).selectValueForUpdate("ai.switch");
        verify(configMapper, never()).selectValue("ai.switch");
        verify(configMapper).upsert(eq("ai.switch"), eq("{\"enabled\":true,\"features\":{\"assistant\":true}}"), eq(1L));
        assertThat(result).isEqualTo(new AiSwitchVO(true, "admin", OffsetDateTime.parse("2026-11-21T15:00:00+08:00")));
        assertThat(OperLogDetail.current()).containsEntry("改前", "关").containsEntry("改后", "开");
    }

    /**
     * 规则：没有这一行（被删了）设置页显示关、没人改过；原来的值不是 JSON 对象时整个换成新的，不报错；
     * 设置页读库出错就报错，不显示成"关"（拦功能的 enabled() 才按关）
     */
    @Test
    void missingRow_offAndBrokenValueReplaced() {
        when(configMapper.selectRow("ai.switch")).thenReturn(null);
        assertThat(service.get()).isEqualTo(new AiSwitchVO(false, null, null));

        when(configMapper.selectValueForUpdate("ai.switch")).thenReturn("坏了", "[1, 2]");
        service.set(false, 1L);
        service.set(false, 1L);
        verify(configMapper, times(2)).upsert("ai.switch", "{\"enabled\":false}", 1L);
        assertThat(OperLogDetail.current()).containsEntry("改前", "关").containsEntry("改后", "关");

        when(configMapper.selectRow("ai.switch")).thenThrow(new RuntimeException("数据库连不上"));
        assertThatThrownBy(() -> service.get()).hasMessageContaining("数据库连不上");
    }
}
