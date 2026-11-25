package com.lingmao.scm.module.style.controller;

import com.lingmao.scm.common.page.PageResult;
import com.lingmao.scm.config.SecurityConfig;
import com.lingmao.scm.module.style.dto.StyleCreateResult;
import com.lingmao.scm.module.style.service.StyleService;
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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/** 带上真的安全配置，测"谁能调"（RFC 005 §12）：没有新增权限调预览 403；名称为空 400；选款式登录即可 */
@WebMvcTest(StyleController.class)
@Import(SecurityConfig.class)
class StyleControllerTest {

    private static final String BODY = """
            {"codeMode": "AUTO", "name": "%s", "styleClass": "FS", "brand": "TB", "categoryId": 3, "year": 2027,
             "season": "SPRING", "sizeGroupId": 7, "designerId": 21, "colorIds": [11, 12]}
            """;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private StyleService styleService;

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

    /** 规则：款号预览要新增权限：只有查看权限的 403，Service 不被调用 */
    @Test
    void codePreview_withoutAddPermission_returns403() throws Exception {
        mockMvc.perform(get("/api/styles/code-preview").param("styleClass", "FS").param("designerId", "21")
                        .with(loginAs("style:style:list")))
                .andExpect(jsonPath("$.code").value(403));

        verify(styleService, never()).codePreview(anyString(), anyLong(), any(), any(), any());
    }

    /** 规则：有新增权限能预览 */
    @Test
    void codePreview_withAddPermission_ok() throws Exception {
        when(styleService.codePreview("FS", 21L, null, null, null)).thenReturn("FS2611080701");

        mockMvc.perform(get("/api/styles/code-preview").param("styleClass", "FS").param("designerId", "21")
                        .with(loginAs("style:style:add")))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").value("FS2611080701"));
    }

    /** 规则：有新增权限，名称为空在入口返回 400 */
    @Test
    void create_blankName_returns400() throws Exception {
        mockMvc.perform(post("/api/styles").with(loginAs("style:style:add"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY.formatted(" ")))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.msg").value("款式名称不能为空"));

        verify(styleService, never()).create(any());
    }

    /** 规则：合法的请求交给 Service，返回实际的款号 */
    @Test
    void create_ok_returnsCode() throws Exception {
        when(styleService.create(any())).thenReturn(new StyleCreateResult(100L, "FS2611080701", 10));

        mockMvc.perform(post("/api/styles").with(loginAs("style:style:add"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY.formatted("测试风衣")))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.code").value("FS2611080701"))
                .andExpect(jsonPath("$.data.skuCount").value(10));
    }

    /** 规则：选款式的弹窗给价格档案、订单用，登录即可；没登录 401 */
    @Test
    void pick_loginOnly() throws Exception {
        when(styleService.pick(any())).thenReturn(new PageResult<>(List.of(), 0));

        mockMvc.perform(get("/api/styles/pick").with(loginAs()))
                .andExpect(jsonPath("$.code").value(0));
        mockMvc.perform(get("/api/styles/pick"))
                .andExpect(jsonPath("$.code").value(401));
    }

    private static RequestPostProcessor loginAs(String... permissions) {
        return authentication(new UsernamePasswordAuthenticationToken(new LoginUser(2L, "jti-2"), null,
                Arrays.stream(permissions).map(SimpleGrantedAuthority::new).toList()));
    }
}
