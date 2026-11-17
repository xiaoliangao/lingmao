package com.lingmao.scm.module.purchase.controller;

import com.lingmao.scm.config.SecurityConfig;
import com.lingmao.scm.module.biz.service.DocCommonService;
import com.lingmao.scm.module.purchase.service.PurchaseOrderService;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

@WebMvcTest(PurchaseOrderController.class)
@Import(SecurityConfig.class)
class PurchaseOrderControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PurchaseOrderService purchaseOrderService;

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

    /**
     * 规则（docs/api/03 §3.3.6）：审核只要登录（谁能审由多级审批判断）；反审核要 purchase:order:audit，
     * 结案要单独的 purchase:order:close
     */
    @Test
    void audit_loginOnly_unauditAndCloseNeedPerms() throws Exception {
        mockMvc.perform(post("/api/purchase-orders/7/audit").with(loginAs())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\": 3}"))
                .andExpect(jsonPath("$.code").value(0));
        verify(purchaseOrderService).act(eq(7L), eq("audit"), any());

        mockMvc.perform(post("/api/purchase-orders/7/unaudit").with(loginAs("purchase:order:list"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\": 4}"))
                .andExpect(jsonPath("$.code").value(403));
        mockMvc.perform(post("/api/purchase-orders/7/close").with(loginAs("purchase:order:audit"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\": 4}"))
                .andExpect(jsonPath("$.code").value(403));
        verify(purchaseOrderService, never()).act(anyLong(), eq("unaudit"), any());
        verify(purchaseOrderService, never()).act(anyLong(), eq("close"), any());
    }

    /** 规则（§3.3.10）：在待到料里改预计到货要 purchase:order:edit，跟进备注最多 255 个字 */
    @Test
    void follow_needsEditAndChecksLength() throws Exception {
        mockMvc.perform(put("/api/purchase-orders/7/items/9/follow").with(loginAs("purchase:order:list"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"expectDate\": \"2026-12-01\"}"))
                .andExpect(jsonPath("$.code").value(403));
        mockMvc.perform(put("/api/purchase-orders/7/items/9/follow").with(loginAs("purchase:order:edit"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"followNote\": \"" + "催".repeat(256) + "\"}"))
                .andExpect(jsonPath("$.code").value(400));
        verify(purchaseOrderService, never()).follow(anyLong(), anyLong(), any());

        mockMvc.perform(put("/api/purchase-orders/7/items/9/follow").with(loginAs("purchase:order:edit"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"expectDate\": \"2026-12-01\", \"followNote\": \"周五发\"}"))
                .andExpect(jsonPath("$.code").value(0));
        verify(purchaseOrderService).follow(eq(7L), eq(9L), any());
    }

    private static RequestPostProcessor loginAs(String... permissions) {
        return authentication(new UsernamePasswordAuthenticationToken(new LoginUser(2L, "jti-2"), null,
                Arrays.stream(permissions).map(SimpleGrantedAuthority::new).toList()));
    }
}
