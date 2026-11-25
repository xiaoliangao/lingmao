package com.lingmao.scm.module.system.controller;

import com.lingmao.scm.config.SecurityConfig;
import com.lingmao.scm.module.system.dto.DeptVO;
import com.lingmao.scm.module.system.service.AiSwitchService;
import com.lingmao.scm.module.system.service.DeptService;
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

/** 带上真的安全配置：看部门树要用户查看权限，改要用户编辑权限；名字空着 400 */
@WebMvcTest(DeptController.class)
@Import(SecurityConfig.class)
class DeptControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DeptService deptService;

    @MockitoBean
    private JwtUtil jwtUtil;

    @MockitoBean
    private LoginSessionStore loginSessionStore;

    @MockitoBean
    private PermissionService permissionService;

    @MockitoBean
    private AiSwitchService aiSwitchService;

    /** 规则：有用户查看权限能看树，id 按字符串输出；只能看的不能加 */
    @Test
    void tree_withList_ok_createNeedsEdit() throws Exception {
        when(deptService.tree()).thenReturn(List.of(new DeptVO(1L, null, "产品部", 0, "产品部", 3)));

        mockMvc.perform(get("/api/depts").with(loginAs("system:user:list")))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data[0].id").value("1"))
                .andExpect(jsonPath("$.data[0].userCount").value(3));
        mockMvc.perform(post("/api/depts").with(loginAs("system:user:list"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\": \"设计组\", \"sort\": 0}"))
                .andExpect(jsonPath("$.code").value(403));
        verify(deptService, never()).create(any());
    }

    /** 规则：名字空着在入口 400，不进 Service */
    @Test
    void create_blankName_400() throws Exception {
        mockMvc.perform(post("/api/depts").with(loginAs("system:user:edit"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\": \" \", \"sort\": 0}"))
                .andExpect(jsonPath("$.code").value(400));
        verify(deptService, never()).create(any());
    }

    private static RequestPostProcessor loginAs(String... permissions) {
        return authentication(new UsernamePasswordAuthenticationToken(new LoginUser(2L, "jti-2"), null,
                Arrays.stream(permissions).map(SimpleGrantedAuthority::new).toList()));
    }
}
