package com.lingmao.scm.security;

import com.lingmao.scm.common.result.R;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 助手令牌只能调返回数据（R）的接口（RFC 017 §13"导出、下载谁来拦"）。
 * 非 GET 在 JwtAuthFilter 就拦了；这里拦返回文件的 GET：导出、下载、打印、模板、预览，和不是接口方法的处理器（静态文件）。
 * 按接口方法的返回类型认，不按路径：以后新加的导出接口不用记得来这里登记。
 * 助手服务的工具白名单再挡一层，但真正的拦截在这里
 */
public class AssistantScopeInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!LoginUser.currentIsAssistant()) {
            return true;
        }
        if (handler instanceof HandlerMethod method && R.class.isAssignableFrom(method.getMethod().getReturnType())) {
            return true;
        }
        throw new AssistantDenied("助手只能查数据，不能导出、下载文件");
    }
}
