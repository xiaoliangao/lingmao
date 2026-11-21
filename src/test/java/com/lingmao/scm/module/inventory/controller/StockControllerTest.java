package com.lingmao.scm.module.inventory.controller;

import com.lingmao.scm.common.page.PageResult;
import com.lingmao.scm.config.SecurityConfig;
import com.lingmao.scm.module.inventory.dto.StockMovementQuery;
import com.lingmao.scm.module.inventory.service.StockLogExportService;
import com.lingmao.scm.module.inventory.service.StockService;
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
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

@WebMvcTest(StockController.class)
@Import(SecurityConfig.class)
class StockControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private StockService stockService;

    @MockitoBean
    private StockLogExportService logExporter;

    @MockitoBean
    private Clock clock;

    @MockitoBean
    private JwtUtil jwtUtil;

    @MockitoBean
    private LoginSessionStore loginSessionStore;

    @MockitoBean
    private PermissionService permissionService;

    /** 规则（docs/api/04 §3.6.6）：进销存的期间必填、仓库类型只认物料仓 / 工厂仓，不对在入口 400，Service 不被调用 */
    @Test
    void movement_needsPeriodAndValidType() throws Exception {
        mockMvc.perform(get("/api/stocks/movement").param("bizDateFrom", "2026-11-01").with(loginAs("inventory:stock:list")))
                .andExpect(jsonPath("$.code").value(400));
        mockMvc.perform(get("/api/stocks/movement").param("bizDateFrom", "2026-11-01").param("bizDateTo", "2026-11-30")
                        .param("warehouseType", "PRODUCT").with(loginAs("inventory:stock:list")))
                .andExpect(jsonPath("$.code").value(400));
        verify(stockService, never()).movement(any());
    }

    /** 规则：条件原样交给 Service；导出要 inventory:stock:export，只有查看权限的 403 */
    @Test
    void movement_passesQuery_exportNeedsPermission() throws Exception {
        when(stockService.movement(any())).thenReturn(new PageResult<>(List.of(), 0));
        mockMvc.perform(get("/api/stocks/movement").param("bizDateFrom", "2026-11-01").param("bizDateTo", "2026-11-30")
                        .param("warehouseType", "FACTORY").param("warehouseId", "7").with(loginAs("inventory:stock:list")))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(0));
        ArgumentCaptor<StockMovementQuery> query = ArgumentCaptor.captor();
        verify(stockService).movement(query.capture());
        assertThat(query.getValue()).extracting(StockMovementQuery::getBizDateFrom, StockMovementQuery::getBizDateTo,
                        StockMovementQuery::getWarehouseType, StockMovementQuery::getWarehouseId)
                .containsExactly(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30), "FACTORY", 7L);

        mockMvc.perform(get("/api/stocks/movement/export").param("bizDateFrom", "2026-11-01").param("bizDateTo", "2026-11-30")
                        .with(loginAs("inventory:stock:list")))
                .andExpect(jsonPath("$.code").value(403));
        verify(stockService, never()).exportMovement(any());
    }

    private static RequestPostProcessor loginAs(String... permissions) {
        return authentication(new UsernamePasswordAuthenticationToken(new LoginUser(1L, "jti"), null,
                Arrays.stream(permissions).map(SimpleGrantedAuthority::new).toList()));
    }
}
