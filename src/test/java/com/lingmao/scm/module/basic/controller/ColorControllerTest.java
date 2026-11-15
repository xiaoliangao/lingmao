package com.lingmao.scm.module.basic.controller;

import com.lingmao.scm.module.basic.service.ColorService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

@WebMvcTest(ColorController.class)
@AutoConfigureMockMvc(addFilters = false)
class ColorControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ColorService colorService;

    /** 规则：颜色编码 2-3 位字母数字（进 SKU 码），色块是 #RRGGBB；不对在入口返回 400 */
    @Test
    void create_badCodeOrHex_returns400() throws Exception {
        mockMvc.perform(post("/api/colors")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code": "KHAKI", "name": "卡其"}
                                """))
                .andExpect(jsonPath("$.code").value(400));
        mockMvc.perform(post("/api/colors")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code": "KQ", "name": "卡其", "hex": "red"}
                                """))
                .andExpect(jsonPath("$.code").value(400));

        verify(colorService, never()).create(any());
    }

    /** 规则：合法的交给 Service，返回新 id */
    @Test
    void create_ok_returnsId() throws Exception {
        when(colorService.create(any())).thenReturn(3L);

        mockMvc.perform(post("/api/colors")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code": "KQ", "name": "卡其", "hex": "#C3B091"}
                                """))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").value(3));
    }
}
