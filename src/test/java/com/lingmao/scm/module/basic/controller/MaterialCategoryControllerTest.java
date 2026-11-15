package com.lingmao.scm.module.basic.controller;

import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.module.basic.service.MaterialCategoryService;
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

@WebMvcTest(MaterialCategoryController.class)
@AutoConfigureMockMvc(addFilters = false)
class MaterialCategoryControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MaterialCategoryService materialCategoryService;

    /** 规则：二级分类的编码是 2 位字母（物料编码的前缀），填 3 位、带数字在入口返回 400 */
    @Test
    void create_badCode_returns400() throws Exception {
        for (String code : new String[]{"MLX", "M1"}) {
            mockMvc.perform(post("/api/material-categories")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"parentId": 1, "code": "%s", "name": "梭织"}
                                    """.formatted(code)))
                    .andExpect(jsonPath("$.code").value(400));
        }
        verify(materialCategoryService, never()).create(any());
    }

    /** 规则：Service 的业务错误原样变成 R */
    @Test
    void create_bizError_passedThrough() throws Exception {
        when(materialCategoryService.create(any())).thenThrow(new BizException("物料分类只有两级，二级下面不能再加"));

        mockMvc.perform(post("/api/material-categories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"parentId": 5, "code": "ML", "name": "梭织"}
                                """))
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.msg").value("物料分类只有两级，二级下面不能再加"));
    }
}
