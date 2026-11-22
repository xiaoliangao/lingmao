package com.lingmao.scm.module.biz.controller;

import com.lingmao.scm.config.SecurityConfig;
import com.lingmao.scm.module.biz.service.ActionRequestService;
import com.lingmao.scm.module.system.service.AiSwitchService;
import com.lingmao.scm.module.system.service.PermissionService;
import com.lingmao.scm.security.JwtUtil;
import com.lingmao.scm.security.LoginSessionStore;
import com.lingmao.scm.security.LoginUser;
import com.lingmao.scm.security.SuperAdminCheck;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/** 提交结果核对：登录就能问，编号不是 UUID 回 400，返回体和前端 utils/request.ts 用的 { done } 对得上 */
@WebMvcTest(ActionRequestController.class)
@Import({SecurityConfig.class, SuperAdminCheck.class})
class ActionRequestControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ActionRequestService actionRequestService;

    /** SecurityConfig 要用的，这里不走 token，给假的 */
    @MockitoBean
    private PermissionService permissionService;

    @MockitoBean
    private AiSwitchService aiSwitchService;
    @MockitoBean
    private JwtUtil jwtUtil;
    @MockitoBean
    private LoginSessionStore loginSessionStore;

    @Test
    void state_loggedIn_returnsDone() throws Exception {
        UUID key = UUID.fromString("b663ca73-bf5e-4fcf-b2e7-131f2611c4db");
        when(actionRequestService.completed(key)).thenReturn(true);

        mockMvc.perform(get("/api/http-actions/" + key).with(authentication(
                        new UsernamePasswordAuthenticationToken(new LoginUser(1L, "jti-1"), null, List.of()))))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.done").value(true));
    }

    @Test
    void state_badKey_returns400() throws Exception {
        mockMvc.perform(get("/api/http-actions/not-a-uuid").with(authentication(
                        new UsernamePasswordAuthenticationToken(new LoginUser(1L, "jti-1"), null, List.of()))))
                .andExpect(jsonPath("$.code").value(400));

        verify(actionRequestService, never()).completed(any());
    }
}
