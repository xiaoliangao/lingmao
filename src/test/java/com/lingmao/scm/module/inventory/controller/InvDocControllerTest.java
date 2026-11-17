package com.lingmao.scm.module.inventory.controller;

import com.lingmao.scm.config.SecurityConfig;
import com.lingmao.scm.module.biz.service.DocCommonService;
import com.lingmao.scm.module.inventory.service.InvDocService;
import com.lingmao.scm.module.system.service.PermissionService;
import com.lingmao.scm.security.JwtUtil;
import com.lingmao.scm.security.LoginSessionStore;
import com.lingmao.scm.security.LoginUser;
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

import java.time.Clock;
import java.util.Arrays;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

@WebMvcTest(InvDocController.class)
@Import(SecurityConfig.class)
class InvDocControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private InvDocService invDocService;

    @MockitoBean
    private DocCommonService docCommonService;

    @MockitoBean
    private Clock clock;

    @MockitoBean
    private JwtUtil jwtUtil;

    @MockitoBean
    private LoginSessionStore loginSessionStore;

    @MockitoBean
    private PermissionService permissionService;

    /** 规则（docs/api/04 §3.2.1）：列表的 kind 必填，没传在入口 400，Service 不被调用 */
    @Test
    void page_needsKind() throws Exception {
        mockMvc.perform(get("/api/inventory-docs").with(loginAs("inventory:doc:list")))
                .andExpect(jsonPath("$.code").value(400));
        verify(invDocService, never()).page(any());
    }

    /** 规则（§3.3）：由源单生成并马上过账要 :add 和 :audit 两个权限，只有 :add 的 403；作废要 :remove 权限、要带 version */
    @Test
    void postAndVoid_checkPermissions() throws Exception {
        mockMvc.perform(post("/api/inventory-docs/from-purchase/88").with(loginAs("inventory:doc:add"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"clientActionId\": \"7a12b243-6a31-4eea-b5bb-af9ba9e4f771\", \"docType\": \"PURCHASE_IN\", \"post\": true}"))
                .andExpect(jsonPath("$.code").value(403));
        verify(invDocService, never()).fromSource(any(), anyLong(), any());

        mockMvc.perform(post("/api/inventory-docs/7/void").with(loginAs("inventory:doc:audit"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\": 2}"))
                .andExpect(jsonPath("$.code").value(403));
        mockMvc.perform(post("/api/inventory-docs/7/void").with(loginAs("inventory:doc:remove"))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(jsonPath("$.code").value(400));
        verify(invDocService, never()).act(anyLong(), anyString(), any());

        mockMvc.perform(post("/api/inventory-docs/7/void").with(loginAs("inventory:doc:remove"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\": 2, \"reason\": \"录重了\"}"))
                .andExpect(jsonPath("$.code").value(0));
        verify(invDocService).act(anyLong(), anyString(), any());
    }

    private static RequestPostProcessor loginAs(String... permissions) {
        return authentication(new UsernamePasswordAuthenticationToken(new LoginUser(2L, "jti-2"), null,
                Arrays.stream(permissions).map(SimpleGrantedAuthority::new).toList()));
    }
}
