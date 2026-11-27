package com.lingmao.scm.security;

import com.lingmao.scm.common.auth.CallerScope;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.resource.ResourceHttpRequestHandler;

/**
 * 工厂账号（RFC 019 §7）只能调标了 {@link FactoryAccess} 的接口，别的一律 403。
 * <p>
 * 为什么不靠权限码：工厂账号一个权限码都没有，带 @PreAuthorize 的接口本来就进不去；但"登录就能调"的接口（选人下拉、字典、
 * 首页、快捷跳转搜单号……）没有权限码，靠这里挡。按接口方法认、默认不让：以后新加的接口不用记得来这里登记，
 * FactoryAccountIT 把路由表里每个没标的接口用工厂账号调一遍，全部 403。
 * <p>
 * /files/**（ResourceHttpRequestHandler）放行：要带签名才能取（SignedFileFilter），签名只在接口返回里给，工厂拿到的都是本厂单子上的
 */
public class FactoryScopeInterceptor implements HandlerInterceptor {

    static final String MESSAGE = "工厂账号只能看本厂的生产单、报进度、登记实裁数";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (CallerScope.factoryId() == null || allowed(handler)) {
            return true;
        }
        throw new FactoryDenied(MESSAGE);
    }

    /** 工厂账号能不能调这个处理器（FactoryAccountIT 用同一个判断列出全部不能调的接口，再逐个调一遍） */
    public static boolean allowed(Object handler) {
        if (handler instanceof ResourceHttpRequestHandler) {
            return true;
        }
        return handler instanceof HandlerMethod method && method.hasMethodAnnotation(FactoryAccess.class);
    }
}
