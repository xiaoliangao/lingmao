package com.lingmao.scm.module.order.controller;

import com.lingmao.scm.config.SecurityConfig;
import com.lingmao.scm.module.biz.service.DocCommonService;
import com.lingmao.scm.module.order.dto.OrderImportHead;
import com.lingmao.scm.module.order.service.OrderService;
import com.lingmao.scm.module.system.service.AiSwitchService;
import com.lingmao.scm.module.system.service.PermissionService;
import com.lingmao.scm.security.JwtUtil;
import com.lingmao.scm.security.LoginSessionStore;
import com.lingmao.scm.security.LoginUser;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

@WebMvcTest(OrderController.class)
@Import(SecurityConfig.class)
class OrderControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private OrderService orderService;

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

    @MockitoBean
    private AiSwitchService aiSwitchService;

    /**
     * 规则（docs/api/02 §3.1、§3.7）：审核不看权限码（谁能审由多级审批判断），但承诺交期必填；反审核要 order:order:audit
     */
    @Test
    void audit_loginOnlyButNeedsCommitDate() throws Exception {
        mockMvc.perform(post("/api/orders/7/audit").with(loginAs())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\": 3}"))
                .andExpect(jsonPath("$.code").value(400));
        verify(orderService, never()).audit(anyLong(), any());

        mockMvc.perform(post("/api/orders/7/audit").with(loginAs())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\": 3, \"commitDate\": \"2026-12-20\"}"))
                .andExpect(jsonPath("$.code").value(0));
        verify(orderService).audit(eq(7L), any());

        mockMvc.perform(post("/api/orders/7/unaudit").with(loginAs("order:order:list"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\": 4}"))
                .andExpect(jsonPath("$.code").value(403));
        verify(orderService, never()).act(anyLong(), eq("unaudit"), any());
    }

    /** 规则（docs/api/02 §3.9）：导入的单头拼在查询参数里，要求交期必填；要 order:order:import */
    @Test
    void import_headFromQuery() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "订单.xlsx", "application/octet-stream", new byte[]{1});
        mockMvc.perform(multipart("/api/orders/import").file(file).with(loginAs("order:order:import"))
                        .param("companyId", "1").param("orderType", "BULK"))
                .andExpect(jsonPath("$.code").value(400));
        verify(orderService, never()).importExcel(any(), any());

        mockMvc.perform(multipart("/api/orders/import").file(file).with(loginAs("order:order:import"))
                        .param("companyId", "1").param("orderType", "BULK").param("requiredDate", "2026-12-31"))
                .andExpect(jsonPath("$.code").value(0));
        ArgumentCaptor<OrderImportHead> head = ArgumentCaptor.forClass(OrderImportHead.class);
        verify(orderService).importExcel(head.capture(), any());
        assertThat(head.getValue().getRequiredDate()).isEqualTo(LocalDate.of(2026, 12, 31));
        assertThat(head.getValue().getSalesId()).isNull();
    }

    private static RequestPostProcessor loginAs(String... permissions) {
        return authentication(new UsernamePasswordAuthenticationToken(new LoginUser(2L, "jti-2"), null,
                Arrays.stream(permissions).map(SimpleGrantedAuthority::new).toList()));
    }
}
