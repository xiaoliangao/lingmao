package com.lingmao.scm.module.basic.controller;

import com.lingmao.scm.module.basic.dto.SizeGroupVO;
import com.lingmao.scm.module.basic.service.SizeGroupService;
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

@WebMvcTest(SizeGroupController.class)
@AutoConfigureMockMvc(addFilters = false)
class SizeGroupControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SizeGroupService sizeGroupService;

    /** 规则：尺码不填在入口返回 400 */
    @Test
    void create_blankSizes_returns400() throws Exception {
        mockMvc.perform(post("/api/size-groups")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "女装字母码", "sizes": ""}
                                """))
                .andExpect(jsonPath("$.code").value(400));

        verify(sizeGroupService, never()).create(any());
    }

    /** 规则：追加尺码走 POST /{id}/sizes，把填的那一串交给 Service */
    @Test
    void appendSizes_passesText() throws Exception {
        mockMvc.perform(post("/api/size-groups/7/sizes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sizes": "XL XXL"}
                                """))
                .andExpect(jsonPath("$.code").value(0));

        verify(sizeGroupService).appendSizes(7L, "XL XXL");
    }

    /** 规则：/options 带尺码，按顺序 */
    @Test
    void options_withSizes() throws Exception {
        when(sizeGroupService.options()).thenReturn(List.of(new SizeGroupVO(7L, "女装字母码", "M", 1, List.of("S", "M", "L"))));

        mockMvc.perform(get("/api/size-groups/options"))
                .andExpect(jsonPath("$.data[0].sizes[1]").value("M"))
                .andExpect(jsonPath("$.data[0].baseSize").value("M"));
    }
}
