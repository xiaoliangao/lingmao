package com.lingmao.scm.common.log;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 错误编号：每个请求生成一个 8 位十六进制编号（traceId），放进 MDC，这个请求打的每一行日志都带上它
 * （日志格式在 application.yaml 的 logging.pattern.correlation）；同时写进响应头 X-Trace-Id，500 的提示里也有。
 * 用户截图发过来，在服务器上 grep 编号就能找到那次请求的全部日志。
 * 在 WebConfig 里注册成最高优先级，排在 Spring Security 前面，所以 401 也有编号。顺便记慢请求。
 */
@Slf4j
public class TraceIdFilter extends OncePerRequestFilter {

    /** MDC 里的键名，日志格式按这个名字取 */
    public static final String MDC_KEY = "traceId";
    public static final String HEADER = "X-Trace-Id";

    private final Duration slowThreshold;

    /** 超过 1 秒的请求记一条 warn */
    public TraceIdFilter() {
        this(Duration.ofSeconds(1));
    }

    /** 测试用：把慢请求的门槛调低，不用真等 1 秒 */
    TraceIdFilter(Duration slowThreshold) {
        this.slowThreshold = slowThreshold;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        // 8 位十六进制约 43 亿种，一天几千个请求，日志保留期内撞上的概率可以忽略；又短到能在电话里念出来
        String traceId = HexFormat.of().toHexDigits(ThreadLocalRandom.current().nextInt());
        MDC.put(MDC_KEY, traceId);
        // 进来就写响应头：后面的过滤器可能直接把响应写完（比如 Spring Security 返回 401），等请求结束再写就晚了
        response.setHeader(HEADER, traceId);
        long start = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            long costMs = (System.nanoTime() - start) / 1_000_000;
            if (costMs >= slowThreshold.toMillis()) {
                log.warn("慢请求: {} {} 耗时 {}ms", request.getMethod(), request.getRequestURI(), costMs);
            }
            // 线程会被下一个请求复用：不清掉，别的请求（还有不在请求里的日志）会带着这个编号
            MDC.remove(MDC_KEY);
        }
    }
}
