package com.lingmao.scm.module.system.controller;

import com.lingmao.scm.module.system.entity.DictData;
import com.lingmao.scm.module.system.service.DictDataService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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

@WebMvcTest(DictDataController.class)
class DictDataControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DictDataService dictDataService;

    /** 规则：value 为空在边界就拦下，返回 400，Service 不会被调用 */
    @Test
    void create_blankValue_returns400AndServiceNotCalled() throws Exception {
        mockMvc.perform(post("/api/dict-data")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"typeCode": "season", "label": "春季", "value": ""}
                                """))
                .andExpect(jsonPath("$.code").value(400));

        verify(dictDataService, never()).create(any());
    }

    /** 规则：by-type 路径接对了，Service 返回的列表原样放进 R 的 data */
    @Test
    void listByType_returnsListInData() throws Exception {
        DictData spring = new DictData();
        spring.setLabel("春季");
        spring.setValue("1");
        when(dictDataService.listByType("season")).thenReturn(List.of(spring));

        mockMvc.perform(get("/api/dict-data/by-type/season"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data[0].label").value("春季"))
                .andExpect(jsonPath("$.data[0].value").value("1"));
    }
}