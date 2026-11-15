package com.lingmao.scm.module.basic.controller;

import com.lingmao.scm.module.basic.dto.CompanyOption;
import com.lingmao.scm.module.basic.service.CompanyService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

@WebMvcTest(CompanyController.class)
@AutoConfigureMockMvc(addFilters = false)
class CompanyControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CompanyService companyService;

    /** 规则（PRD §6.1.1）：编码是 2-6 位字母数字，填 1 位、带汉字、7 位都在入口返回 400 */
    @Test
    void create_badCode_returns400() throws Exception {
        for (String code : new String[]{"A", "公司01", "ABCDEFG"}) {
            mockMvc.perform(post("/api/companies")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"code": "%s", "name": "某某服饰有限公司"}
                                    """.formatted(code)))
                    .andExpect(jsonPath("$.code").value(400));
        }
        verify(companyService, never()).create(any());
    }

    /** 规则：/options 只给 id、编码、简称（单据上选主体用），不会被当成 /{id} */
    @Test
    void options_returnsIdCodeShortName() throws Exception {
        when(companyService.options()).thenReturn(List.of(new CompanyOption(1L, "SH", "上海公司")));

        mockMvc.perform(get("/api/companies/options"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data[0].code").value("SH"))
                .andExpect(jsonPath("$.data[0].shortName").value("上海公司"));
    }
}
