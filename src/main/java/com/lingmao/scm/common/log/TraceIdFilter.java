package com.lingmao.scm.common.log;

import com.lingmao.scm.common.result.R;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.time.Duration;
import java.util.UUID;

/**
 * Canonical X-Request-Id is validated or replaced with a UUID, echoed with the legacy X-Trace-Id,
 * and scoped to the servlet thread. Unknown filter failures return only a safe message and this ID.
 */
@Slf4j
public class TraceIdFilter extends OncePerRequestFilter {

    /** MDC 里的键名，日志格式按这个名字取 */
    public static final String MDC_KEY = "traceId";
    public static final String HEADER = "X-Trace-Id";
    public static final String REQUEST_HEADER = "X-Request-Id";

    /** One bounded opaque identifier, never an arbitrary string supplied by a client. */
    public static String normalized(String value) {
        return isValid(value) ? value : UUID.randomUUID().toString();
    }

    public static boolean isValid(String value) {
        return value != null && value.length() >= 8 && value.length() <= 64
                && value.matches("[A-Za-z0-9][A-Za-z0-9._:-]{7,63}");
    }

    private final JsonMapper jsonMapper;
    private final Duration slowThreshold;

    /** 超过 1 秒的请求记一条 warn */
    public TraceIdFilter(JsonMapper jsonMapper) {
        this(jsonMapper, Duration.ofSeconds(1));
    }

    /** 测试用：把慢请求的门槛调低，不用真等 1 秒 */
    TraceIdFilter(JsonMapper jsonMapper, Duration slowThreshold) {
        this.jsonMapper = jsonMapper;
        this.slowThreshold = slowThreshold;
    }

    /** 500 给前端的提示：带上这个请求的错误编号。GlobalExceptionHandler 的兜底和这里接住的过滤器异常用同一句 */
    public static String serverErrorMsg() {
        String traceId = MDC.get(MDC_KEY);
        return traceId == null ? "服务器出错了" : "服务器出错了，错误编号 " + traceId;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String supplied = request.getHeader(REQUEST_HEADER);
        String traceId = normalized(supplied == null ? request.getHeader(HEADER) : supplied);
        String previous = MDC.get(MDC_KEY);
        MDC.put(MDC_KEY, traceId);
        // 进来就写响应头：后面的过滤器可能直接把响应写完（比如 Spring Security 返回 401），等请求结束再写就晚了
        response.setHeader(HEADER, traceId);
        response.setHeader(REQUEST_HEADER, traceId);
        long start = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } catch (RuntimeException | ServletException e) {
            // 过滤器里抛的异常到不了 GlobalExceptionHandler，比如 JwtAuthFilter 每个请求查权限时数据库、Redis 出错。
            // 不在这里接住的话，Tomcat 转去 /error，那里又要登录：前端收到 401"登录已过期"、被踢回登录页；
            // 堆栈由 Tomcat 打，那时编号已经清掉了，搜不到（2c 测出来的）。所以在这里记日志、按兜底的样子回 500
            log.error("未处理异常（过滤器）: {} {} {}", request.getMethod(), request.getRequestURI(), SafeLog.failure(e));
            if (response.isCommitted()) {
                throw e;   // 响应已经发出去一部分，改不了了
            }
            response.resetBuffer();
            response.setStatus(HttpServletResponse.SC_OK);
            response.setContentType("application/json;charset=UTF-8");
            jsonMapper.writeValue(response.getWriter(), R.fail(500, serverErrorMsg()));
        } finally {
            long costMs = (System.nanoTime() - start) / 1_000_000;
            if (costMs >= slowThreshold.toMillis()) {
                log.warn("慢请求: {} {} 耗时 {}ms", request.getMethod(), request.getRequestURI(), costMs);
            }
            // 线程会被下一个请求复用：不清掉，别的请求（还有不在请求里的日志）会带着这个编号
            if (previous == null) MDC.remove(MDC_KEY); else MDC.put(MDC_KEY, previous);
        }
    }
}
