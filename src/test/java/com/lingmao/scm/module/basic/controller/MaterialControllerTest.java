package com.lingmao.scm.module.basic.controller;

import com.lingmao.scm.common.page.PageResult;
import com.lingmao.scm.config.SecurityConfig;
import com.lingmao.scm.module.basic.service.MaterialService;
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
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/** 带上真的安全配置，测"谁能调"：没有新增权限 403；名称为空 400；选 SKU 登录即可（RFC 003 §16） */
@WebMvcTest(MaterialController.class)
@Import(SecurityConfig.class)
class MaterialControllerTest {

    private static final String BODY = """
            {"name": "%s", "categoryId": 5, "materialType": "FABRIC", "stockUnit": "M", "colorIds": [1, 2]}
            """;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MaterialService materialService;

    /** 导出文件名带日期要用 */
    @MockitoBean
    private Clock clock;

    /** SecurityConfig 要用，这里不走 token，给假的 */
    @MockitoBean
    private JwtUtil jwtUtil;

    @MockitoBean
    private LoginSessionStore loginSessionStore;

    @MockitoBean
    private PermissionService permissionService;

    @MockitoBean
    private AiSwitchService aiSwitchService;

    /** 规则：只有查看权限，新建返回 403，Service 不被调用 */
    @Test
    void create_withoutAddPermission_returns403() throws Exception {
        mockMvc.perform(post("/api/materials").with(loginAs("basic:material:list"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY.formatted("全棉府绸")))
                .andExpect(jsonPath("$.code").value(403));

        verify(materialService, never()).create(any());
    }

    /** 规则：有新增权限，名称为空在入口返回 400 */
    @Test
    void create_blankName_returns400() throws Exception {
        mockMvc.perform(post("/api/materials").with(loginAs("basic:material:add"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY.formatted(" ")))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.msg").value("名称不能为空"));

        verify(materialService, never()).create(any());
    }

    /** 规则：有新增权限，合法的请求交给 Service，返回新 id */
    @Test
    void create_ok_returnsId() throws Exception {
        when(materialService.create(any())).thenReturn(100L);

        mockMvc.perform(post("/api/materials").with(loginAs("basic:material:add"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY.formatted("全棉府绸")))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").value("100"));
    }

    /** 规则：选 SKU 的弹窗给下游各页用，登录即可，不要物料的权限；没登录 401 */
    @Test
    void pick_loginOnly() throws Exception {
        when(materialService.pick(any())).thenReturn(new PageResult<>(List.of(), 0));

        mockMvc.perform(get("/api/materials/pick").with(loginAs()))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(0));
        mockMvc.perform(get("/api/materials/pick"))
                .andExpect(jsonPath("$.code").value(401));
    }

    private static RequestPostProcessor loginAs(String... permissions) {
        return authentication(new UsernamePasswordAuthenticationToken(new LoginUser(2L, "jti-2"), null,
                Arrays.stream(permissions).map(SimpleGrantedAuthority::new).toList()));
    }
}
