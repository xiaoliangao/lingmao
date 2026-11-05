package com.lingmao.scm.module.system.controller;

import com.lingmao.scm.module.system.service.FileService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

@WebMvcTest(FileController.class)
@AutoConfigureMockMvc(addFilters = false)
class FileControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private FileService fileService;

    /** 规则（bug 007）：上传时没带 file 字段，返回 400 并说缺了什么，Service 不会被调用 */
    @Test
    void upload_noFile_returns400() throws Exception {
        mockMvc.perform(multipart("/api/files"))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.msg").value("缺少参数 file"));

        verify(fileService, never()).upload(any());
    }
}
