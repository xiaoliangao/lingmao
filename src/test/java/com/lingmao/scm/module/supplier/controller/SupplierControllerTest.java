package com.lingmao.scm.module.supplier.controller;

import com.lingmao.scm.config.SecurityConfig;
import com.lingmao.scm.module.supplier.dto.BankVO;
import com.lingmao.scm.module.supplier.service.SupplierCertService;
import com.lingmao.scm.module.supplier.service.SupplierChangeService;
import com.lingmao.scm.module.supplier.service.SupplierExcelService;
import com.lingmao.scm.module.supplier.service.SupplierService;
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

import java.time.Clock;
import java.util.Arrays;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/** 供应商接口的权限（RFC 004 §8、§15）：带上真的安全配置，测"谁能调" */
@WebMvcTest(SupplierController.class)
@Import({SecurityConfig.class, SuperAdminCheck.class})
class SupplierControllerTest {
    @MockitoBean private com.lingmao.scm.common.excel.ExcelDownloads excelDownloads;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SupplierService supplierService;
    @MockitoBean
    private SupplierChangeService changeService;
    @MockitoBean
    private SupplierCertService certService;
    @MockitoBean
    private SupplierExcelService excelService;
    @MockitoBean
    private Clock clock;

    /** SecurityConfig 要用的，这里不走 token，给假的 */
    @MockitoBean
    private PermissionService permissionService;
    @MockitoBean
    private JwtUtil jwtUtil;
    @MockitoBean
    private LoginSessionStore loginSessionStore;

    /** 规则：没有准入权限（admit）调通过 → 403，Service 不被调 */
    @Test
    void audit_withoutAdmit_returns403() throws Exception {
        mockMvc.perform(post("/api/suppliers/1/audit").with(loginAs("list", "edit")))
                .andExpect(jsonPath("$.code").value(403));

        verify(supplierService, never()).audit(anyLong());
    }

    /** 规则：有 admit 能调通过 */
    @Test
    void audit_withAdmit_ok() throws Exception {
        mockMvc.perform(post("/api/suppliers/1/audit").with(loginAs("list", "admit")))
                .andExpect(jsonPath("$.code").value(0));

        verify(supplierService).audit(1L);
    }

    /** 规则：没有 bank 权限取全号 → 403 */
    @Test
    void bank_withoutBank_returns403() throws Exception {
        mockMvc.perform(get("/api/suppliers/1/bank").with(loginAs("list", "edit", "admit")))
                .andExpect(jsonPath("$.code").value(403));

        verify(supplierService, never()).bank(anyLong());
    }

    /** 规则：有 bank 权限拿到全号 */
    @Test
    void bank_withBank_returnsFullAccount() throws Exception {
        when(supplierService.bank(1L)).thenReturn(new BankVO("测试银行", "6222000011112222333", "测试户名"));

        mockMvc.perform(get("/api/suppliers/1/bank").with(loginAs("list", "bank")))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.bankAccount").value("6222000011112222333"));
    }

    /** 规则：驳回要填原因，空的 400 */
    @Test
    void reject_blankReason_returns400() throws Exception {
        mockMvc.perform(post("/api/suppliers/1/reject").with(loginAs("admit"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"  \"}"))
                .andExpect(jsonPath("$.code").value(400));

        verify(supplierService, never()).reject(anyLong(), org.mockito.ArgumentMatchers.anyString());
    }

    /** 登录用户 1，带这几个权限码（补上前缀 supplier:supplier:） */
    private static RequestPostProcessor loginAs(String... perms) {
        return authentication(new UsernamePasswordAuthenticationToken(new LoginUser(1L, "jti-1"), null,
                Arrays.stream(perms).map(p -> new SimpleGrantedAuthority("supplier:supplier:" + p)).toList()));
    }
}
