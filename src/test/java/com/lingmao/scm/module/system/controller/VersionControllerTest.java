package com.lingmao.scm.module.system.controller;

import com.lingmao.scm.config.SecurityConfig;
import com.lingmao.scm.module.system.service.PermissionService;
import com.lingmao.scm.security.JwtUtil;
import com.lingmao.scm.security.LoginSessionStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Properties;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/** 带上真的安全配置：测的是"不用登录" */
@WebMvcTest(VersionController.class)
@Import({SecurityConfig.class, VersionControllerTest.PackagedBuild.class})
class VersionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    /** SecurityConfig 要用，这里用不上，给假的 */
    @MockitoBean
    private JwtUtil jwtUtil;

    @MockitoBean
    private LoginSessionStore loginSessionStore;

    @MockitoBean
    private PermissionService permissionService;

    /** 规则：不带 token 也能查；返回打包时传进来的版本号，打包时间按东八区输出 */
    @Test
    void version_withoutLogin_returnsPackagedVersion() throws Exception {
        mockMvc.perform(get("/api/version"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.version").value("2026.11.14"))
                .andExpect(jsonPath("$.data.buildTime").value("2026-11-13 21:05:00"));
    }

    /** 模拟打包脚本打出来的 jar：build-info.properties 里有 release 和 time（Spring Boot 读的时候去掉了 build. 前缀） */
    @TestConfiguration
    static class PackagedBuild {
        @Bean
        BuildProperties buildProperties() {
            Properties props = new Properties();
            props.setProperty("release", "2026.11.14");
            props.setProperty("time", "2026-11-13T13:05:00Z");
            return new BuildProperties(props);
        }
    }
}
