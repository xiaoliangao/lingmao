package com.lingmao.scm.module.board.controller;

import com.lingmao.scm.config.SecurityConfig;
import com.lingmao.scm.module.board.dto.FactoryPerfRow;
import com.lingmao.scm.module.board.dto.OrderMarginPage;
import com.lingmao.scm.module.board.dto.PerfResult;
import com.lingmao.scm.module.board.service.ReportService;
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

import java.math.BigDecimal;
import java.time.Clock;
import java.util.Arrays;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

@WebMvcTest(ReportController.class)
@Import(SecurityConfig.class)
class ReportControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ReportService reportService;
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

    /** 供应商绩效要 report:supplier:list、日期必填；订单毛利另要看订单价格、看成本；呆滞天数改要 report:stale:edit */
    @Test
    void permsAndValidation() throws Exception {
        mockMvc.perform(get("/api/reports/supplier-performance/materials").with(loginAs("report:alert:list"))
                        .param("from", "2026-12-01").param("to", "2026-12-07"))
                .andExpect(jsonPath("$.code").value(403));
        mockMvc.perform(get("/api/reports/supplier-performance/materials").with(loginAs("report:supplier:list")).param("from", "2026-12-01"))
                .andExpect(jsonPath("$.code").value(400));
        when(reportService.factoryPerf(any())).thenReturn(new PerfResult<>(List.of(), new FactoryPerfRow()));
        mockMvc.perform(get("/api/reports/supplier-performance/factories").with(loginAs("report:supplier:list"))
                        .param("from", "2026-12-01").param("to", "2026-12-07"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.rows").isArray());

        mockMvc.perform(get("/api/reports/order-margins").with(loginAs("report:margin:list", "order:price:query"))
                        .param("from", "2026-12-01").param("to", "2026-12-07"))
                .andExpect(jsonPath("$.code").value(403));
        verify(reportService, never()).orderMargins(any());
        when(reportService.orderMargins(any())).thenReturn(new OrderMarginPage(List.of(), 0, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, null, 0, 0));
        mockMvc.perform(get("/api/reports/order-margins").with(loginAs("report:margin:list", "order:price:query", "techpack:cost:query"))
                        .param("from", "2026-12-01").param("to", "2026-12-07"))
                .andExpect(jsonPath("$.code").value(0));

        mockMvc.perform(put("/api/reports/stale-materials/days").with(loginAs("report:stale:list"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"days\":120}"))
                .andExpect(jsonPath("$.code").value(403));
        mockMvc.perform(put("/api/reports/stale-materials/days").with(loginAs("report:stale:edit"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"days\":0}"))
                .andExpect(jsonPath("$.code").value(400));
        mockMvc.perform(put("/api/reports/stale-materials/days").with(loginAs("report:stale:edit"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"days\":120}"))
                .andExpect(jsonPath("$.code").value(0));
        verify(reportService).setStaleDays(120);
    }

    private static RequestPostProcessor loginAs(String... permissions) {
        return authentication(new UsernamePasswordAuthenticationToken(new LoginUser(2L, "jti-2"), null,
                Arrays.stream(permissions).map(SimpleGrantedAuthority::new).toList()));
    }
}
