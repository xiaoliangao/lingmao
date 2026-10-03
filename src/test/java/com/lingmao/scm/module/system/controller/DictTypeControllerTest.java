package com.lingmao.scm.module.system.controller;

import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.module.system.service.DictTypeService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * 接口测试样板：测"HTTP 这一层"有没有接对——路径、参数校验、异常怎么变成 R。
 * @WebMvcTest 只起 Web 层（Controller + 全局异常处理），不起数据库和 MyBatis，几秒跑完。
 * Service 用 @MockitoBean 换成假的：业务规则已经在 ServiceImplTest 里测过，这里不重复测。
 * 注意本项目出错时 HTTP 状态码仍是 200，错误放在 R 的 code 里，所以断言的是 $.code 而不是 status()。
 */
@WebMvcTest(DictTypeController.class)
class DictTypeControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DictTypeService dictTypeService;

    /** 规则：入参不合法在边界就拦下，返回 400，Service 根本不会被调用。 */
    @Test
    void create_blankCode_returns400AndServiceNotCalled() throws Exception {
        mockMvc.perform(post("/api/dict-types")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code": "", "name": "季节"}
                                """))
                .andExpect(jsonPath("$.code").value(400));

        verify(dictTypeService, never()).create(any());
    }

    /** 规则：Service 抛的 BizException 经全局异常处理器变成 {code: 404, msg: ...}，Controller 里没有 try/catch。 */
    @Test
    void get_notFound_returns404Body() throws Exception {
        when(dictTypeService.getById(99L)).thenThrow(new BizException(404, "字典类型不存在: 99"));

        mockMvc.perform(get("/api/dict-types/99"))
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.msg").value("字典类型不存在: 99"));
    }
}
