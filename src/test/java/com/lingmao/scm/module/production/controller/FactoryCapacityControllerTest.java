package com.lingmao.scm.module.production.controller;

import com.lingmao.scm.config.SecurityConfig;
import com.lingmao.scm.module.production.dto.CapacitySaveReq;
import com.lingmao.scm.module.production.service.FactoryCapacityService;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

@WebMvcTest(FactoryCapacityController.class)
@Import(SecurityConfig.class)
class FactoryCapacityControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private FactoryCapacityService factoryCapacityService;
    @MockitoBean
    private JwtUtil jwtUtil;
    @MockitoBean
    private LoginSessionStore loginSessionStore;
    @MockitoBean
    private PermissionService permissionService;
    @MockitoBean
    private AiSwitchService aiSwitchService;

    /** 看要 production:capacity:list，登记要 production:capacity:edit；月份格式不对 400；排单看产能要排单或改生产单的权限 */
    @Test
    void permsAndValidation() throws Exception {
        when(factoryCapacityService.list(any())).thenReturn(List.of());
        mockMvc.perform(get("/api/factory-capacities").with(loginAs("production:order:list")))
                .andExpect(jsonPath("$.code").value(403));
        mockMvc.perform(get("/api/factory-capacities").with(loginAs("production:capacity:list")).param("month", "2026-12"))
                .andExpect(jsonPath("$.code").value(0));
        mockMvc.perform(get("/api/factory-capacities").with(loginAs("production:capacity:list")).param("month", "12月"))
                .andExpect(jsonPath("$.code").value(400));

        String body = "{\"factoryId\":7,\"month\":\"2026-12\",\"dailyQty\":120}";
        mockMvc.perform(post("/api/factory-capacities").with(loginAs("production:capacity:list"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(jsonPath("$.code").value(403));
        verify(factoryCapacityService, never()).save(any());
        mockMvc.perform(post("/api/factory-capacities").with(loginAs("production:capacity:edit"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"factoryId\":7,\"month\":\"2026-12\",\"dailyQty\":0}"))
                .andExpect(jsonPath("$.code").value(400));
        when(factoryCapacityService.save(any())).thenReturn(5L);
        mockMvc.perform(post("/api/factory-capacities").with(loginAs("production:capacity:edit"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(jsonPath("$.code").value(0));
        ArgumentCaptor<CapacitySaveReq> req = ArgumentCaptor.forClass(CapacitySaveReq.class);
        verify(factoryCapacityService).save(req.capture());
        assertThat(req.getValue().getDailyQty()).isEqualTo(120);
        assertThat(req.getValue().getCategoryId()).isNull();

        when(factoryCapacityService.preview(7L, 3L, LocalDate.of(2026, 12, 20), null)).thenReturn(List.of());
        mockMvc.perform(get("/api/factory-capacities/preview").with(loginAs("production:capacity:list"))
                        .param("factoryId", "7").param("styleId", "3").param("dueDate", "2026-12-20"))
                .andExpect(jsonPath("$.code").value(403));
        mockMvc.perform(get("/api/factory-capacities/preview").with(loginAs("production:order:add"))
                        .param("factoryId", "7").param("styleId", "3").param("dueDate", "2026-12-20"))
                .andExpect(jsonPath("$.code").value(0));
        verify(factoryCapacityService).preview(7L, 3L, LocalDate.of(2026, 12, 20), null);
    }

    private static RequestPostProcessor loginAs(String... permissions) {
        return authentication(new UsernamePasswordAuthenticationToken(new LoginUser(2L, "jti-2"), null,
                Arrays.stream(permissions).map(SimpleGrantedAuthority::new).toList()));
    }
}
