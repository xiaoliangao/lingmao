package com.lingmao.scm.module.production.controller;

import com.lingmao.scm.config.SecurityConfig;
import com.lingmao.scm.module.biz.service.DocCommonService;
import com.lingmao.scm.module.production.service.ProductionIssueService;
import com.lingmao.scm.module.production.service.ProductionNodeService;
import com.lingmao.scm.module.production.service.ProductionOrderService;
import com.lingmao.scm.module.production.service.ProductionReceiptService;
import com.lingmao.scm.module.production.service.ScheduleService;
import com.lingmao.scm.module.system.service.AiSwitchService;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

@WebMvcTest(ProductionOrderController.class)
@Import(SecurityConfig.class)
class ProductionOrderControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ProductionOrderService productionOrderService;
    @MockitoBean
    private ScheduleService scheduleService;
    @MockitoBean
    private ProductionNodeService nodeService;
    @MockitoBean
    private ProductionIssueService issueService;
    @MockitoBean
    private ProductionReceiptService receiptService;
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
     * 规则（docs/api/05 §3.2.5、§3.8）：审核只要登录；反审核要 production:order:audit；
     * 手机报工只有 production:mobile:list 也能看详情（点进一张单），但看不了列表
     */
    @Test
    void auditLoginOnly_unauditNeedsPerm_mobileCanOpenDetail() throws Exception {
        mockMvc.perform(post("/api/production-orders/7/audit").with(loginAs())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\": 3}"))
                .andExpect(jsonPath("$.code").value(0));
        verify(productionOrderService).act(eq(7L), eq("audit"), any());

        mockMvc.perform(post("/api/production-orders/7/unaudit").with(loginAs("production:order:list"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\": 4}"))
                .andExpect(jsonPath("$.code").value(403));
        verify(productionOrderService, never()).act(anyLong(), eq("unaudit"), any());

        mockMvc.perform(get("/api/production-orders/7").with(loginAs("production:mobile:list")))
                .andExpect(jsonPath("$.code").value(0));
        mockMvc.perform(get("/api/production-orders").param("kind", "OUTSOURCE").with(loginAs("production:mobile:list")))
                .andExpect(jsonPath("$.code").value(403));
    }

    /** 规则（§3.6.3）：作废回货单要 production:receipt:void，原因必填（400） */
    @Test
    void voidReceipt_needsPermAndReason() throws Exception {
        mockMvc.perform(post("/api/production-orders/7/receipts/9/void").with(loginAs("production:receipt:add"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\": \"点错了\"}"))
                .andExpect(jsonPath("$.code").value(403));
        mockMvc.perform(post("/api/production-orders/7/receipts/9/void").with(loginAs("production:receipt:void"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\": \" \"}"))
                .andExpect(jsonPath("$.code").value(400));
        verify(receiptService, never()).voidReceipt(anyLong(), anyLong(), anyString());

        mockMvc.perform(post("/api/production-orders/7/receipts/9/void").with(loginAs("production:receipt:void"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\": \"点错了\"}"))
                .andExpect(jsonPath("$.code").value(0));
        verify(receiptService).voidReceipt(7L, 9L, "点错了");
    }

    private static RequestPostProcessor loginAs(String... permissions) {
        return authentication(new UsernamePasswordAuthenticationToken(new LoginUser(2L, "jti-2"), null,
                Arrays.stream(permissions).map(SimpleGrantedAuthority::new).toList()));
    }
}
