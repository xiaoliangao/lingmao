package com.lingmao.scm.module.basic.controller;

import com.lingmao.scm.module.basic.dto.TreeNode;
import com.lingmao.scm.module.basic.service.CategoryService;
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

@WebMvcTest(CategoryController.class)
@AutoConfigureMockMvc(addFilters = false)
class CategoryControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CategoryService categoryService;

    /** 规则：名称为空在入口返回 400 */
    @Test
    void create_blankName_returns400() throws Exception {
        mockMvc.perform(post("/api/categories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"parentId": null, "name": ""}
                                """))
                .andExpect(jsonPath("$.code").value(400));

        verify(categoryService, never()).create(any());
    }

    /** 规则：/tree 给下拉用，下级放在 children 里 */
    @Test
    void tree_nested() throws Exception {
        TreeNode top = new TreeNode(1L, null, null, "上装", 1, 0, 1);
        TreeNode child = new TreeNode(2L, 1L, null, "衬衫", 2, 0, 1);
        when(categoryService.enabledTree()).thenReturn(TreeNode.build(List.of(top, child)));

        mockMvc.perform(get("/api/categories/tree"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data[0].name").value("上装"))
                .andExpect(jsonPath("$.data[0].children[0].name").value("衬衫"));
    }
}
