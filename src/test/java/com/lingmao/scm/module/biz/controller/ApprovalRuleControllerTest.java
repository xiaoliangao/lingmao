package com.lingmao.scm.module.biz.controller;

import com.lingmao.scm.config.SecurityConfig;
import com.lingmao.scm.module.biz.dto.ApprovalRuleSaveReq;
import com.lingmao.scm.module.biz.dto.ApprovalRuleVO;
import com.lingmao.scm.module.biz.entity.BizType;
import com.lingmao.scm.module.biz.service.ApprovalRuleService;
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

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/** 设置 › 审批的接口（架构 §4.8）：带上真的安全配置，测谁能调、返回的样子和前端 types/biz.ts 的 ApprovalRule 对得上 */
@WebMvcTest(ApprovalRuleController.class)
@Import({SecurityConfig.class, SuperAdminCheck.class})
class ApprovalRuleControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ApprovalRuleService approvalRuleService;

    /** SecurityConfig 要用的，这里不走 token，给假的 */
    @MockitoBean
    private PermissionService permissionService;
    @MockitoBean
    private JwtUtil jwtUtil;
    @MockitoBean
    private LoginSessionStore loginSessionStore;

    /** 规则：有 list 能看，字段名和前端一致（bizType 是枚举名，名字翻好了） */
    @Test
    void list_withList_returnsRules() throws Exception {
        when(approvalRuleService.list()).thenReturn(List.of(
                new ApprovalRuleVO(1L, BizType.PURCHASE_ORDER, new BigDecimal("50000.00"), 5L, "总经理", null, null)));

        mockMvc.perform(get("/api/approval-rules").with(loginAs("list")))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data[0].bizType").value("PURCHASE_ORDER"))
                .andExpect(jsonPath("$.data[0].overAmount").value(50000.00))
                .andExpect(jsonPath("$.data[0].roleName").value("总经理"))
                .andExpect(jsonPath("$.data[0].userId").isEmpty());
    }

    /** 规则：只有 list 没有 edit，新增 → 403，Service 不被调 */
    @Test
    void create_withoutEdit_returns403() throws Exception {
        mockMvc.perform(post("/api/approval-rules").with(loginAs("list"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bizType\":\"ORDER\",\"overAmount\":100000,\"roleId\":5}"))
                .andExpect(jsonPath("$.code").value(403));

        verify(approvalRuleService, never()).create(any());
    }

    /** 规则：金额是负数 → 400，Service 不被调 */
    @Test
    void create_negativeAmount_returns400() throws Exception {
        mockMvc.perform(post("/api/approval-rules").with(loginAs("edit"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bizType\":\"ORDER\",\"overAmount\":-1,\"roleId\":5}"))
                .andExpect(jsonPath("$.code").value(400));

        verify(approvalRuleService, never()).create(any(ApprovalRuleSaveReq.class));
    }

    private static RequestPostProcessor loginAs(String... perms) {
        return authentication(new UsernamePasswordAuthenticationToken(new LoginUser(1L, "jti-1"), null,
                Arrays.stream(perms).map(p -> new SimpleGrantedAuthority("biz:approval-rule:" + p)).toList()));
    }
}
