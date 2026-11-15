package com.lingmao.scm.module.system.controller;

import com.lingmao.scm.common.page.PageResult;
import com.lingmao.scm.module.system.dto.UserOption;
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
                .andExpect(jsonPath("$.msg").value("密码长度 8-32 位"));

        verify(userService, never()).create(any());
    }

    /**
     * 规则（RFC 001 §18）：BCrypt 最多认 72 个字节。25 个汉字只有 25 个字符（@Size 放行），却有 75 个字节，
     * 在入口返回 400，不能走到加密那一步变成 500
     */
    @Test
    void create_passwordOver72Bytes_returns400() throws Exception {
        String password = "密".repeat(25);
        mockMvc.perform(post("/api/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username": "zhangsan", "password": "%s", "nickname": "张三"}
                                """.formatted(password)))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.msg").value("密码太长：最多 72 个字节，一个汉字算 3 个"));

        verify(userService, never()).create(any());
    }

    /** 规则（RFC 005 §3.3）：设计师编号是两位数字 01-99，填 7、00、A1 都返回 400；不填可以 */
    @Test
    void create_designerNoNotTwoDigits_returns400() throws Exception {
        for (String no : new String[]{"7", "00", "A1"}) {
            mockMvc.perform(post("/api/users")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"username": "zhangsan", "password": "Abc12345", "nickname": "张三", "designerNo": "%s"}
                                    """.formatted(no)))
                    .andExpect(jsonPath("$.code").value(400));
        }
        verify(userService, never()).create(any());
    }

    /** 规则（RFC 001 §18 1d）：选人下拉的接口，关键字传给 Service，只返回 id、用户名、昵称；/options 不会被当成 /{id} */
    @Test
    void options_returnsIdUsernameNickname() throws Exception {
        when(userService.options("zh")).thenReturn(List.of(new UserOption(2L, "zhangsan", "张三")));

        mockMvc.perform(get("/api/users/options").param("keyword", "zh"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data[0].id").value(2))
                .andExpect(jsonPath("$.data[0].username").value("zhangsan"))
                .andExpect(jsonPath("$.data[0].nickname").value("张三"))
                .andExpect(jsonPath("$.data[0].phone").doesNotExist());
    }

    /** 规则：列表返回的每个用户里都没有 password 字段 */
    @Test
    void page_noPasswordInResponse() throws Exception {
        UserVO zhangsan = new UserVO(2L, "zhangsan", "张三", null, 1, null, null, null, null, null, false);
        when(userService.page(any())).thenReturn(new PageResult<>(List.of(zhangsan), 1));

        mockMvc.perform(get("/api/users"))
                .andExpect(jsonPath("$.data.list[0].username").value("zhangsan"))
                .andExpect(jsonPath("$.data.list[0].password").doesNotExist());
    }
}
