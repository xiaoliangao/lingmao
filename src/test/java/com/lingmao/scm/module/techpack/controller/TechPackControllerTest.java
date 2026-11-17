package com.lingmao.scm.module.techpack.controller;

import com.lingmao.scm.config.SecurityConfig;
import com.lingmao.scm.module.system.service.PermissionService;
import com.lingmao.scm.module.techpack.dto.TechPackCostVO;
import com.lingmao.scm.module.techpack.service.TechPackCostService;
import com.lingmao.scm.module.techpack.service.TechPackService;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

@WebMvcTest(TechPackController.class)
@Import(SecurityConfig.class)
class TechPackControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TechPackService techPackService;

    @MockitoBean
    private TechPackCostService costService;

    /** SecurityConfig 要用，这里不走 token，给假的 */
    @MockitoBean
    private JwtUtil jwtUtil;

    @MockitoBean
    private LoginSessionStore loginSessionStore;

    @MockitoBean
    private PermissionService permissionService;

    /** 规则（docs/api/01 §3.10）：核价要 techpack:cost:query，只能看资料包的 403，Service 不被调用；有权限的照常返回 */
    @Test
    void cost_needsCostPermission() throws Exception {
        mockMvc.perform(get("/api/tech-packs/7/cost").with(loginAs("techpack:pack:list")))
                .andExpect(jsonPath("$.code").value(403));
        verify(costService, never()).cost(anyLong(), any());

        when(costService.cost(7L, null)).thenReturn(new TechPackCostVO());
        mockMvc.perform(get("/api/tech-packs/7/cost").with(loginAs("techpack:cost:query")))
                .andExpect(jsonPath("$.code").value(0));
    }

    /** 规则：生效要带 versionLock，没带在入口 400；只有编辑权限的点生效 403 */
    @Test
    void publish_validatesAndChecksPermission() throws Exception {
        mockMvc.perform(post("/api/tech-packs/7/publish").with(loginAs("techpack:pack:publish"))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(jsonPath("$.code").value(400));
        mockMvc.perform(post("/api/tech-packs/7/publish").with(loginAs("techpack:pack:edit"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"versionLock\": 3}"))
                .andExpect(jsonPath("$.code").value(403));
        verify(techPackService, never()).publish(anyLong(), anyInt());
    }

    private static RequestPostProcessor loginAs(String... permissions) {
        return authentication(new UsernamePasswordAuthenticationToken(new LoginUser(2L, "jti-2"), null,
                Arrays.stream(permissions).map(SimpleGrantedAuthority::new).toList()));
    }
}
