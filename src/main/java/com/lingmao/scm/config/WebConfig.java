package com.lingmao.scm.config;

import com.lingmao.scm.security.AssistantScopeInterceptor;
import com.lingmao.scm.common.log.TraceIdFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Paths;

/** Web 层配置：/files/** 映射到上传目录（上传的图片、附件用这个地址访问，要带签名，校验在 FileAccessConfig）；错误编号过滤器 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final String uploadLocation;

    public WebConfig(@Value("${lingmao.upload.dir}") String uploadDir) {
        this.uploadLocation = Paths.get(uploadDir).toAbsolutePath().normalize().toUri().toString();
    }

    /** 助手令牌只能调返回数据的接口（RFC 017 §13），导出、下载、模板、预览回 403 */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new AssistantScopeInterceptor());
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/files/**").addResourceLocations(uploadLocation);
    }

    /**
     * 错误编号过滤器排在所有过滤器前面。Spring Security 的过滤器链排在 -100，
     * 不排到它前面的话，未登录返回的 401 没有编号，认证过程中打的日志也不带编号
     */
    @Bean
    public FilterRegistrationBean<TraceIdFilter> traceIdFilter(JsonMapper jsonMapper) {
        FilterRegistrationBean<TraceIdFilter> registration = new FilterRegistrationBean<>(new TraceIdFilter(jsonMapper));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }
}
