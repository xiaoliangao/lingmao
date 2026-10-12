package com.lingmao.scm.module.system.controller;

import com.lingmao.scm.module.system.service.OperLogService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

@WebMvcTest(OperLogController.class)
@AutoConfigureMockMvc(addFilters = false)
class OperLogControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private OperLogService operLogService;

    /** 规则：一页最多 500 条，超过在入口就拦下 */
    @Test
    void page_sizeTooLarge_returns400() throws Exception {
        mockMvc.perform(get("/api/oper-logs").param("size", "1000"))
                .andExpect(jsonPath("$.code").value(400));

        verify(operLogService, never()).page(any());
    }
}
