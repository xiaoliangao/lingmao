package com.lingmao.scm.common.file;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * /files/** 的签名校验，和 Nginx 的 secure_link 一样（deploy/package/nginx/site.conf）：没签名、签名不对 403，过期 410。
 * 开发环境没有 Nginx，文件由应用自己发（WebConfig 的映射），靠它挡住；生产的 /files/ 由 Nginx 直接发、不经过应用，开着也无妨。
 * 不要求登录（<img> 带不了 token），只认签名。在 FileAccessConfig 里注册，只管 /files/*
 */
public class SignedFileFilter extends OncePerRequestFilter {

    private final FileUrlSigner signer;

    public SignedFileFilter(FileUrlSigner signer) {
        this.signer = signer;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        // 用请求里原样的路径（没解码）。我们发出去的地址都是 ASCII、不带 % 和 ..，和 Nginx 解码、规整后的 $uri 是同一个串；
        // 换个写法（编码过的、多了斜杠的）这里一律算签名不对，只会比 Nginx 严
        switch (signer.verify(request.getRequestURI(), request.getParameter("e"), request.getParameter("s"))) {
            case VALID -> chain.doFilter(request, response);
            case EXPIRED -> reject(response, HttpServletResponse.SC_GONE, "地址已过期，回到系统里刷新页面再打开");
            case INVALID -> reject(response, HttpServletResponse.SC_FORBIDDEN, "地址没有签名或签名不对");
        }
    }

    /** 直接写状态码和一句话，不用 sendError：那样会转到 Spring Boot 的 /error 再绕一圈安全检查，返回什么就不由这里定了 */
    private static void reject(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("text/plain;charset=UTF-8");
        response.getWriter().write(message);
    }
}
