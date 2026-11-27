package com.lingmao.scm.module.production.controller;

import com.lingmao.scm.common.page.PageResult;
import com.lingmao.scm.config.SecurityConfig;
import com.lingmao.scm.module.production.dto.LedgerOrderQuery;
import com.lingmao.scm.module.production.dto.LedgerStatus;
import com.lingmao.scm.module.production.service.FactoryLedgerService;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Clock;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

@WebMvcTest(FactoryLedgerController.class)
@Import(SecurityConfig.class)
class FactoryLedgerControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private FactoryLedgerService factoryLedgerService;
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

    /** 要 production:ledger:list（只有生产单的查看不行）；料账状态按枚举名绑上 */
    @Test
    void needsLedgerPerm_bindsStatus() throws Exception {
        mockMvc.perform(get("/api/factory-ledger/orders").with(loginAs("production:order:list")))
                .andExpect(jsonPath("$.code").value(403));
        verify(factoryLedgerService, never()).orders(any());

        when(factoryLedgerService.orders(any())).thenReturn(new PageResult<>(List.of(), 0));
        mockMvc.perform(get("/api/factory-ledger/orders").with(loginAs("production:ledger:list"))
                        .param("status", "TO_SETTLE").param("factoryId", "7").param("keyword", "JG"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(0));
        ArgumentCaptor<LedgerOrderQuery> q = ArgumentCaptor.forClass(LedgerOrderQuery.class);
        verify(factoryLedgerService).orders(q.capture());
        assertThat(q.getValue().getStatus()).isEqualTo(LedgerStatus.TO_SETTLE);
        assertThat(q.getValue().getFactoryId()).isEqualTo(7L);

        when(factoryLedgerService.counts(any())).thenReturn(Map.of("TO_SETTLE", 2L));
        mockMvc.perform(get("/api/factory-ledger/orders/counts").with(loginAs("production:ledger:list")))
                .andExpect(jsonPath("$.data.TO_SETTLE").value(2));

        // 导出另要 production:ledger:export
        mockMvc.perform(get("/api/factory-ledger/materials/export").with(loginAs("production:ledger:list")))
                .andExpect(jsonPath("$.code").value(403));
    }

    private static RequestPostProcessor loginAs(String... permissions) {
        return authentication(new UsernamePasswordAuthenticationToken(new LoginUser(2L, "jti-2"), null,
                Arrays.stream(permissions).map(SimpleGrantedAuthority::new).toList()));
    }
}
