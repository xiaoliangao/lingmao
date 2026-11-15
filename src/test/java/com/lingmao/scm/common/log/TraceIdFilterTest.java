package com.lingmao.scm.common.log;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.MDC;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(OutputCaptureExtension.class)
class TraceIdFilterTest {

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    /** 规则：处理请求时 MDC 里有编号（8 位十六进制），和响应头里的是同一个；请求结束就清掉，不串到下一个请求 */
    @Test
    void traceIdInMdcAndHeader_clearedAfterRequest() throws Exception {
        AtomicReference<String> duringRequest = new AtomicReference<>();
        MockHttpServletResponse response = new MockHttpServletResponse();

        new TraceIdFilter(jsonMapper).doFilter(new MockHttpServletRequest("GET", "/api/users"), response,
                (req, res) -> duringRequest.set(MDC.get(TraceIdFilter.MDC_KEY)));

        assertThat(duringRequest.get()).matches("[0-9a-f]{8}");
        assertThat(response.getHeader(TraceIdFilter.HEADER)).isEqualTo(duringRequest.get());
        assertThat(MDC.get(TraceIdFilter.MDC_KEY)).isNull();
    }

    /**
     * 规则：后面的过滤器抛了异常（比如查权限时数据库出错）→ 回 500 和错误编号（就是响应头里那个），记一条 error，
     * 不让它掉到 Tomcat 的 /error 变成 401；MDC 照样清掉
     */
    @Test
    void chainThrows_returns500WithTraceId(CapturedOutput output) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        new TraceIdFilter(jsonMapper).doFilter(new MockHttpServletRequest("GET", "/api/users"), response,
                (req, res) -> {
                    throw new IllegalStateException("模拟的数据库错误");
                });

        String traceId = response.getHeader(TraceIdFilter.HEADER);
        String body = response.getContentAsString(StandardCharsets.UTF_8);
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat((Integer) JsonPath.read(body, "$.code")).isEqualTo(500);
        assertThat((String) JsonPath.read(body, "$.msg")).isEqualTo("服务器出错了，错误编号 " + traceId);
        assertThat(output.getOut()).contains("未处理异常（过滤器）: GET /api/users").contains("模拟的数据库错误");
        assertThat(MDC.get(TraceIdFilter.MDC_KEY)).isNull();
    }

    /** 规则：响应已经发出去一部分时改不了，日志照记，异常照样抛出去；MDC 照样清掉 */
    @Test
    void chainThrowsAfterCommit_rethrows(CapturedOutput output) {
        assertThatThrownBy(() -> new TraceIdFilter(jsonMapper).doFilter(
                new MockHttpServletRequest("GET", "/api/export"), new MockHttpServletResponse(), (req, res) -> {
                    res.getWriter().write("前半截");
                    res.flushBuffer();
                    throw new IllegalStateException("写到一半出错");
                }))
                .isInstanceOf(IllegalStateException.class);

        assertThat(output.getOut()).contains("未处理异常（过滤器）: GET /api/export");
        assertThat(MDC.get(TraceIdFilter.MDC_KEY)).isNull();
    }

    /** 规则：慢请求记一条 warn，写清方法、路径、耗时（门槛调成 0，每个请求都算慢） */
    @Test
    void slowRequest_logsWarn(CapturedOutput output) throws Exception {
        new TraceIdFilter(jsonMapper, Duration.ZERO).doFilter(new MockHttpServletRequest("POST", "/api/orders"),
                new MockHttpServletResponse(), new MockFilterChain());

        assertThat(output.getOut()).contains("慢请求: POST /api/orders 耗时");
    }
}
