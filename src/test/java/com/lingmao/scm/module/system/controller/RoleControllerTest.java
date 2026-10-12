package com.lingmao.scm.module.system.controller;

import com.lingmao.scm.module.system.service.RoleService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

@WebMvcTest(RoleController.class)
@AutoConfigureMockMvc(addFilters = false)
class RoleControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RoleService roleService;

    /** 规则：角色编码不合规，入口就返回 400 */
    @Test
    void create_badCode_returns400() throws Exception {
        mockMvc.perform(post("/api/roles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code": "Keeper", "name": "仓管"}
                                """))
                .andExpect(jsonPath("$.code").value(400));

        verify(roleService, never()).create(any());
    }

    /** 规则：分配菜单时 ids 不能不传（全部取消要明确传 []） */
    @Test
    void assignMenus_missingIds_returns400() throws Exception {
        mockMvc.perform(put("/api/roles/2/menus")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.msg").value("ids 不能为空，全部取消请传 []"));

        verify(roleService, never()).assignMenus(anyLong(), anyList());
    }
}
