package com.lingmao.scm.module.production.controller;

import com.lingmao.scm.common.page.PageResult;
import com.lingmao.scm.config.SecurityConfig;
import com.lingmao.scm.module.production.dto.OrderProgressCounts;
import com.lingmao.scm.module.production.dto.OrderProgressQuery;
import com.lingmao.scm.module.production.service.OrderProgressService;
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

@WebMvcTest(OrderProgressController.class)
@Import(SecurityConfig.class)
class OrderProgressControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private OrderProgressService progressService;
    @MockitoBean
    private Clock clock;
    @MockitoBean
    private JwtUtil jwtUtil;
    @MockitoBean
    private LoginSessionStore loginSessionStore;
    @MockitoBean
    private PermissionService permissionService;

    /** 要 order:progress:list（只有需求订单的查看不行）；筛选参数照名字绑上 */
    @Test
    void needsProgressPerm_bindsFilters() throws Exception {
        mockMvc.perform(get("/api/order-progress").with(loginAs("order:order:list")))
                .andExpect(jsonPath("$.code").value(403));
        verify(progressService, never()).page(any());

        when(progressService.page(any())).thenReturn(new PageResult<>(List.of(), 0));
        when(progressService.counts(any())).thenReturn(new OrderProgressCounts(3, 1, 0, 2, 1));
        mockMvc.perform(get("/api/order-progress").with(loginAs("order:progress:list"))
                        .param("segment", "LATE").param("brand", "B1").param("mine", "true").param("dueDateTo", "2026-12-31"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(0));
        ArgumentCaptor<OrderProgressQuery> q = ArgumentCaptor.forClass(OrderProgressQuery.class);
        verify(progressService).page(q.capture());
        assertThat(q.getValue().getSegment()).isEqualTo("LATE");
        assertThat(q.getValue().getBrand()).isEqualTo("B1");
        assertThat(q.getValue().isMine()).isTrue();
        assertThat(q.getValue().getDueDateTo()).isEqualTo(LocalDate.of(2026, 12, 31));

        mockMvc.perform(get("/api/order-progress/counts").with(loginAs("order:progress:list")))
                .andExpect(jsonPath("$.data.total").value(3))
                .andExpect(jsonPath("$.data.late").value(2));
    }

    private static RequestPostProcessor loginAs(String... permissions) {
        return authentication(new UsernamePasswordAuthenticationToken(new LoginUser(2L, "jti-2"), null,
                Arrays.stream(permissions).map(SimpleGrantedAuthority::new).toList()));
    }
}
