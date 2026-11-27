package com.lingmao.scm.module.quality.controller;

import com.lingmao.scm.config.SecurityConfig;
import com.lingmao.scm.module.biz.service.DocCommonService;
import com.lingmao.scm.module.quality.dto.QcCreatedVO;
import com.lingmao.scm.module.quality.service.QcInspectionService;
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

import java.util.Arrays;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/** 质检接口（RFC 019 §6）：只有查看权限的（财务、采购）不能记；记的时候日期、结论必填 */
@WebMvcTest(QcInspectionController.class)
@Import(SecurityConfig.class)
class QcInspectionControllerTest {

    private static final String BODY = """
            {"kind": "FINAL", "sourceId": 31, "inspectDate": "2026-11-28", "sampleQty": 32, "defectQty": 0, "result": "PASS"}
            """;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private QcInspectionService qcService;

    @MockitoBean
    private DocCommonService docCommonService;

    @MockitoBean
    private AiSwitchService aiSwitchService;

    @MockitoBean
    private PermissionService permissionService;

    @MockitoBean
    private JwtUtil jwtUtil;

    @MockitoBean
    private LoginSessionStore loginSessionStore;

    /** 规则：只能看的记质检 403，不进 Service；有验货权限的走到 Service */
    @Test
    void create_needsEditPermission() throws Exception {
        mockMvc.perform(post("/api/qc-inspections").with(loginAs("quality:inspection:list"))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(jsonPath("$.code").value(403));
        verify(qcService, never()).create(any());

        when(qcService.create(any())).thenReturn(new QcCreatedVO(50L, "ZJ202611280001"));
        mockMvc.perform(post("/api/qc-inspections").with(loginAs("quality:inspection:edit"))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.no").value("ZJ202611280001"));
    }

    /** 规则：没填结论 → 400，说缺什么 */
    @Test
    void create_validates() throws Exception {
        mockMvc.perform(post("/api/qc-inspections").with(loginAs("quality:inspection:edit"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\": \"FINAL\", \"sourceId\": 31, \"inspectDate\": \"2026-11-28\", \"sampleQty\": 32}"))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.msg").value(org.hamcrest.Matchers.containsString("结论必选")));
        verify(qcService, never()).create(any());
    }

    private static RequestPostProcessor loginAs(String... permissions) {
        return authentication(new UsernamePasswordAuthenticationToken(new LoginUser(2L, "jti-2"), null,
                Arrays.stream(permissions).map(SimpleGrantedAuthority::new).toList()));
    }
}
