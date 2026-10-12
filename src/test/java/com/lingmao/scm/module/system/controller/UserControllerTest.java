package com.lingmao.scm.module.system.controller;

import com.lingmao.scm.common.page.PageResult;
import com.lingmao.scm.module.system.dto.UserVO;
import com.lingmao.scm.module.system.service.UserService;
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

@WebMvcTest(UserController.class)
@AutoConfigureMockMvc(addFilters = false)
class UserControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UserService userService;

    /** 规则：密码太短，在入口就返回 400，Service 不会被调用 */
    @Test
    void create_shortPassword_returns400AndServiceNotCalled() throws Exception {
        mockMvc.perform(post("/api/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username": "zhangsan", "password": "123", "nickname": "张三"}
                                """))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.msg").value("password 密码长度 6-32"));

        verify(userService, never()).create(any());
    }

    /** 规则：列表返回的每个用户里都没有 password 字段 */
    @Test
    void page_noPasswordInResponse() throws Exception {
        UserVO zhangsan = new UserVO(2L, "zhangsan", "张三", null, 1, null);
        when(userService.page(any())).thenReturn(new PageResult<>(List.of(zhangsan), 1));

        mockMvc.perform(get("/api/users"))
                .andExpect(jsonPath("$.data.list[0].username").value("zhangsan"))
                .andExpect(jsonPath("$.data.list[0].password").doesNotExist());
    }
}
