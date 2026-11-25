package com.lingmao.scm.module.basic.controller;

import com.lingmao.scm.common.excel.ImportError;
import com.lingmao.scm.common.excel.ImportResult;
import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.module.basic.service.MaterialCategoryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

@WebMvcTest(MaterialCategoryController.class)
@AutoConfigureMockMvc(addFilters = false)
class MaterialCategoryControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MaterialCategoryService materialCategoryService;

    /** 规则（RFC 018 §8.3）：分类编码是 2 位字母或数字（03 也行），填 3 位、1 位、带横杠在入口返回 400 */
    @Test
    void create_badCode_returns400() throws Exception {
        for (String code : new String[]{"MLX", "M", "M-"}) {
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

    /** 规则（RFC 018 §8.7）：导入收 multipart 的 file，结果（条数 + 提示）原样返回 */
    @Test
    void importExcel_returnsTotalAndNotes() throws Exception {
        when(materialCategoryService.importExcel(any())).thenReturn(ImportResult.of(114,
                java.util.List.of(new ImportError(0, null, "第 3 行和系统里已有的一样，没改"))));

        mockMvc.perform(multipart("/api/material-categories/import")
                        .file(new MockMultipartFile("file", "物料类目.xlsx", "application/octet-stream", new byte[]{1})))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(114))
                .andExpect(jsonPath("$.data.notes[0].message").value("第 3 行和系统里已有的一样，没改"));
    }
}
