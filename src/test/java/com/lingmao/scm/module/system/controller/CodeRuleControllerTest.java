package com.lingmao.scm.module.system.controller;

import com.lingmao.scm.config.SecurityConfig;
import com.lingmao.scm.module.system.dto.CodeTarget;
import com.lingmao.scm.module.system.service.AiSwitchService;
import com.lingmao.scm.module.system.service.CodeRuleService;
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
import org.springframework.security.core.authority.SimpleGrantedAuthority;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/** 编码规则只给超级管理员（RFC 018 §4.6）：有菜单权限码也不行，按角色看 */
@WebMvcTest(CodeRuleController.class)
@Import({SecurityConfig.class, SuperAdminCheck.class})
class CodeRuleControllerTest {

    private static final String BODY = """
            {"target": "STYLE", "name": "鞋", "scopeValues": ["XZ"],
             "segments": [{"type": "TEXT", "text": "X"}, {"type": "SEQ", "width": 3}]}
            """;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CodeRuleService codeRuleService;

    @MockitoBean
    private JwtUtil jwtUtil;

    @MockitoBean
    private LoginSessionStore loginSessionStore;

    @MockitoBean
    private PermissionService permissionService;

    @MockitoBean
    private AiSwitchService aiSwitchService;

    /** 规则：不是超级管理员，就算带着 system:code-rule:list 也 403，不去动规则 */
    @Test
    void notSuperAdmin_403() throws Exception {
        when(permissionService.loadRoleCodes(2L)).thenReturn(List.of("manager"));

        mockMvc.perform(get("/api/code-rules").param("target", "STYLE").with(loginAs(2L, "system:code-rule:list")))
                .andExpect(jsonPath("$.code").value(403));
        mockMvc.perform(post("/api/code-rules").with(loginAs(2L, "system:code-rule:list"))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(jsonPath("$.code").value(403));
        verify(codeRuleService, never()).list(any());
        verify(codeRuleService, never()).create(any());
    }

    /** 规则：超级管理员能加，段的类型按名字收 */
    @Test
    void superAdmin_creates() throws Exception {
        when(permissionService.loadRoleCodes(1L)).thenReturn(List.of("admin"));
        when(codeRuleService.create(any())).thenReturn(9L);

        mockMvc.perform(post("/api/code-rules").with(loginAs(1L)).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").value("9"));
        verify(codeRuleService).create(org.mockito.ArgumentMatchers.argThat(r -> r.getTarget() == CodeTarget.STYLE
                && r.getSegments().size() == 2 && r.getSegments().get(1).width() == 3));
    }

    private static RequestPostProcessor loginAs(Long userId, String... permissions) {
        return authentication(new UsernamePasswordAuthenticationToken(new LoginUser(userId, "jti-" + userId), null,
                java.util.Arrays.stream(permissions).map(SimpleGrantedAuthority::new).toList()));
    }
}
