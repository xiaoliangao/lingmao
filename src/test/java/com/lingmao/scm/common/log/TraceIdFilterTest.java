package com.lingmao.scm.common.log;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.MDC;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(OutputCaptureExtension.class)
class TraceIdFilterTest {

    /** 规则：处理请求时 MDC 里有编号（8 位十六进制），和响应头里的是同一个；请求结束就清掉，不串到下一个请求 */
    @Test
    void traceIdInMdcAndHeader_clearedAfterRequest() throws Exception {
        AtomicReference<String> duringRequest = new AtomicReference<>();
        MockHttpServletResponse response = new MockHttpServletResponse();

        new TraceIdFilter().doFilter(new MockHttpServletRequest("GET", "/api/users"), response,
                (req, res) -> duringRequest.set(MDC.get(TraceIdFilter.MDC_KEY)));

        assertThat(duringRequest.get()).matches("[0-9a-f]{8}");
        assertThat(response.getHeader(TraceIdFilter.HEADER)).isEqualTo(duringRequest.get());
        assertThat(MDC.get(TraceIdFilter.MDC_KEY)).isNull();
    }

    /** 规则：后面抛了异常也要清掉 MDC（线程回到线程池，下一个请求还会用它） */
    @Test
    void chainThrows_mdcStillCleared() {
        assertThatThrownBy(() -> new TraceIdFilter().doFilter(new MockHttpServletRequest("GET", "/api/users"),
                new MockHttpServletResponse(), (req, res) -> {
                    throw new IllegalStateException("模拟的程序错误");
                }))
                .isInstanceOf(IllegalStateException.class);

        assertThat(MDC.get(TraceIdFilter.MDC_KEY)).isNull();
    }

    /** 规则：慢请求记一条 warn，写清方法、路径、耗时（门槛调成 0，每个请求都算慢） */
    @Test
    void slowRequest_logsWarn(CapturedOutput output) throws Exception {
        new TraceIdFilter(Duration.ZERO).doFilter(new MockHttpServletRequest("POST", "/api/orders"),
                new MockHttpServletResponse(), new MockFilterChain());

        assertThat(output.getOut()).contains("慢请求: POST /api/orders 耗时");
    }
}
