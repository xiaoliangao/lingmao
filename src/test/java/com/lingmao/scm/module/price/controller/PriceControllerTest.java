package com.lingmao.scm.module.price.controller;

import com.lingmao.scm.config.SecurityConfig;
import com.lingmao.scm.module.price.dto.PriceQuery;
import com.lingmao.scm.module.price.dto.PriceSaveReq;
import com.lingmao.scm.module.price.service.PriceService;
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

import java.util.Arrays;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/** 价格档案接口的权限和入参（RFC 006 §7、§12）：带上真的安全配置，测"谁能调" */
@WebMvcTest(PriceController.class)
@Import({SecurityConfig.class, SuperAdminCheck.class})
class PriceControllerTest {

    private static final String BODY = """
            {"type":"MATERIAL","supplierId":1,"materialId":10,"price":19.00}""";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PriceService priceService;

    /** SecurityConfig 要用的，这里不走 token，给假的 */
    @MockitoBean
    private PermissionService permissionService;
    @MockitoBean
    private JwtUtil jwtUtil;
    @MockitoBean
    private LoginSessionStore loginSessionStore;

    /** 规则：两个改价权限都没有（只能看）→ 录、作废都 403，Service 不被调 */
    @Test
    void recordAndVoid_withoutEditPermissions_return403() throws Exception {
        mockMvc.perform(post("/api/prices").with(loginAs("price:price:list"))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(jsonPath("$.code").value(403));
        mockMvc.perform(post("/api/prices/9/void").with(loginAs("price:price:list"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"录错了\"}"))
                .andExpect(jsonPath("$.code").value(403));

        verify(priceService, never()).record(any());
        verify(priceService, never()).voidPrice(anyLong(), anyString());
    }

    /** 规则：有其中一个就放进 Service（按类型的细查在 Service 里） */
    @Test
    void record_withProcessPermission_reachesService() throws Exception {
        when(priceService.record(any(PriceSaveReq.class))).thenReturn(77L);

        mockMvc.perform(post("/api/prices").with(loginAs("price:process:edit"))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").value("77"));
    }

    /** 规则：作废不带原因（空白也算）→ 400，Service 不被调 */
    @Test
    void void_blankReason_returns400() throws Exception {
        mockMvc.perform(post("/api/prices/9/void").with(loginAs("price:material:edit"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"  \"}"))
                .andExpect(jsonPath("$.code").value(400));

        verify(priceService, never()).voidPrice(anyLong(), anyString());
    }

    /** 规则：没有 price:price:list 看不了价格记录 */
    @Test
    void page_withoutList_returns403() throws Exception {
        mockMvc.perform(get("/api/prices").param("materialId", "10").with(loginAs("basic:material:list")))
                .andExpect(jsonPath("$.code").value(403));

        verify(priceService, never()).page(any(PriceQuery.class));
    }

    /** 登录用户 1，带这几个权限码 */
    private static RequestPostProcessor loginAs(String... perms) {
        return authentication(new UsernamePasswordAuthenticationToken(new LoginUser(1L, "jti-1"), null,
                Arrays.stream(perms).map(SimpleGrantedAuthority::new).toList()));
    }
}
