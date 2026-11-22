package com.lingmao.scm.module.fg.controller;

import com.lingmao.scm.config.SecurityConfig;
import com.lingmao.scm.module.biz.service.DocCommonService;
import com.lingmao.scm.module.fg.service.FgDocService;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

@WebMvcTest(FgDocController.class)
@Import(SecurityConfig.class)
class FgDocControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private FgDocService fgDocService;

    @MockitoBean
    private DocCommonService docCommonService;

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

    /** 规则（docs/api/07 §3.2）：列表的 kind 必填，没传在入口 400；只看库存的人（fg:stock:list）看不了单据 403 */
    @Test
    void page_needsKindAndPermission() throws Exception {
        mockMvc.perform(get("/api/fg-docs").with(loginAs("fg:doc:list")))
                .andExpect(jsonPath("$.code").value(400));
        mockMvc.perform(get("/api/fg-docs").param("kind", "IN").with(loginAs("fg:stock:list")))
                .andExpect(jsonPath("$.code").value(403));
        verify(fgDocService, never()).page(any());
    }

    /** 规则（§3.3）：由回货单生成并马上入库要 :add 和 :audit，只有 :add 的 403；存草稿只要 :add */
    @Test
    void fromReceiptWithPost_needsAudit() throws Exception {
        mockMvc.perform(post("/api/fg-docs/from-receipt/9").with(loginAs("fg:doc:add"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"clientActionId\": \"7a12b243-6a31-4eea-b5bb-af9ba9e4f771\", \"post\": true}"))
                .andExpect(jsonPath("$.code").value(403));
        verify(fgDocService, never()).fromReceipt(anyLong(), any());

        mockMvc.perform(post("/api/fg-docs/from-receipt/9").with(loginAs("fg:doc:add"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"clientActionId\": \"7a12b243-6a31-4eea-b5bb-af9ba9e4f771\", \"post\": false}"))
                .andExpect(jsonPath("$.code").value(0));
        verify(fgDocService).fromReceipt(anyLong(), any());
    }

    private static RequestPostProcessor loginAs(String... permissions) {
        return authentication(new UsernamePasswordAuthenticationToken(new LoginUser(2L, "jti-2"), null,
                Arrays.stream(permissions).map(SimpleGrantedAuthority::new).toList()));
    }
}
