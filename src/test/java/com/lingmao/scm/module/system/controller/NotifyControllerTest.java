package com.lingmao.scm.module.system.controller;

import com.lingmao.scm.config.SecurityConfig;
import com.lingmao.scm.module.system.dto.NotifyEvent;
import com.lingmao.scm.module.system.dto.NotifySettingsVO;
import com.lingmao.scm.module.system.service.AiSwitchService;
import com.lingmao.scm.module.system.service.NotifyService;
import com.lingmao.scm.module.system.service.PermissionService;
import com.lingmao.scm.security.JwtUtil;
import com.lingmao.scm.security.LoginSessionStore;
import com.lingmao.scm.security.LoginUser;
import com.lingmao.scm.security.SuperAdminCheck;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/** 消息推送只给超级管理员（RFC 019 §4）：带上真的安全配置测 */
@WebMvcTest(NotifyController.class)
@Import({SecurityConfig.class, SuperAdminCheck.class})
class NotifyControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private NotifyService notifyService;

    @MockitoBean
    private PermissionService permissionService;

    @MockitoBean
    private AiSwitchService aiSwitchService;

    @MockitoBean
    private JwtUtil jwtUtil;

    @MockitoBean
    private LoginSessionStore loginSessionStore;

    /** 规则：超级管理员看得到配置状态和种类；改种类按枚举名收，不认识的名字 400 */
    @Test
    void superAdmin_seesAndSetsEvents() throws Exception {
        when(permissionService.loadRoleCodes(1L)).thenReturn(List.of("admin"));
        NotifySettingsVO vo = new NotifySettingsVO(false, false,
                List.of(new NotifySettingsVO.EventOption("DOC_REJECTED", "单据被驳回", "说明", true)), 2);
        when(notifyService.settings()).thenReturn(vo);
        when(notifyService.setEvents(List.of(NotifyEvent.DUE_WARNING))).thenReturn(vo);

        mockMvc.perform(get("/api/admin/notify").with(loginAs(1L)))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.configured").value(false))
                .andExpect(jsonPath("$.data.events[0].value").value("DOC_REJECTED"))
                .andExpect(jsonPath("$.data.failedCount").value(2));
        mockMvc.perform(put("/api/admin/notify/events").with(loginAs(1L))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"events\": [\"DUE_WARNING\"]}"))
                .andExpect(jsonPath("$.code").value(0));
        verify(notifyService).setEvents(List.of(NotifyEvent.DUE_WARNING));

        mockMvc.perform(put("/api/admin/notify/events").with(loginAs(1L))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"events\": [\"NO_SUCH\"]}"))
                .andExpect(jsonPath("$.code").value(400));
    }

    /** 规则：不是超级管理员 → 403，看不到也改不了 */
    @Test
    void notSuperAdmin_returns403() throws Exception {
        when(permissionService.loadRoleCodes(2L)).thenReturn(List.of("manager"));

        mockMvc.perform(get("/api/admin/notify").with(loginAs(2L))).andExpect(jsonPath("$.code").value(403));
        mockMvc.perform(put("/api/admin/notify/events").with(loginAs(2L))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"events\": []}"))
                .andExpect(jsonPath("$.code").value(403));
        verify(notifyService, never()).settings();
        verify(notifyService, never()).setEvents(any());
    }

    private static RequestPostProcessor loginAs(Long userId) {
        return authentication(new UsernamePasswordAuthenticationToken(new LoginUser(userId, "jti-" + userId), null, List.of()));
    }
}
