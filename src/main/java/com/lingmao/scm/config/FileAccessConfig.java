package com.lingmao.scm.config;

import com.lingmao.scm.common.file.FileUrlSigner;
import com.lingmao.scm.common.file.SignedFileFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * /files/** 要带签名才能访问（架构 §8）：WebConfig 把它映射到上传目录，这里在前面挂上 SignedFileFilter。
 * 单独一个配置类、不写进 WebConfig：@WebMvcTest 会加载 WebConfig（它是 WebMvcConfigurer），
 * 但不加载 FileUrlSigner 和 Clock，写在一起的话所有接口测试都起不来
 */
@Configuration
public class FileAccessConfig {

    @Bean
    public FilterRegistrationBean<SignedFileFilter> signedFileFilter(FileUrlSigner signer) {
        FilterRegistrationBean<SignedFileFilter> registration = new FilterRegistrationBean<>(new SignedFileFilter(signer));
        registration.addUrlPatterns("/files/*");
        return registration;
    }
}
