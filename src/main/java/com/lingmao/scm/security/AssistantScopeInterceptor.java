package com.lingmao.scm.security;

import com.lingmao.scm.common.result.R;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.MethodParameter;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.OutputStream;
import java.io.Writer;

/**
 * 助手令牌只能调返回数据（R）的接口（RFC 017 §13"导出、下载谁来拦"）。非 GET 在 JwtAuthFilter 就拦了，这里拦三种：
 * <ul>
 *   <li>返回的不是 R：导出、下载、模板、盘点表，和不是接口方法的处理器（静态文件）；</li>
 *   <li>参数里拿响应对象自己往外写的（HttpServletResponse、OutputStream、Writer，以及能取到响应的 WebRequest），返回类型再像数据也不放；</li>
 *   <li>标了 {@link NoAssistant} 的：工艺单 Excel 预览、供应商银行账号。</li>
 * </ul>
 * 按接口方法本身认，不按路径：以后新加的导出接口不用记得来这里登记。
 * 文件的签名地址另由 FileUrlSigner 不发给助手令牌。助手服务的工具白名单再挡一层，但真正的拦截在这里
 */
public class AssistantScopeInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!LoginUser.currentIsAssistant() || allowed(handler)) {
            return true;
        }
        throw new AssistantDenied("助手只能查数据，不能导出、下载文件");
    }

    /** 助手令牌能不能调这个处理器（AssistantTokenIT 用同一个判断列出全部不能调的接口，再逐个调一遍） */
    public static boolean allowed(Object handler) {
        if (!(handler instanceof HandlerMethod method)) {
            return false;
        }
        if (!R.class.isAssignableFrom(method.getMethod().getReturnType()) || method.hasMethodAnnotation(NoAssistant.class)) {
            return false;
        }
        for (MethodParameter p : method.getMethodParameters()) {
            Class<?> type = p.getParameterType();
            if (ServletResponse.class.isAssignableFrom(type) || OutputStream.class.isAssignableFrom(type) || Writer.class.isAssignableFrom(type)
                    || WebRequest.class.isAssignableFrom(type)) {
                return false;
            }
        }
        return true;
    }
}
