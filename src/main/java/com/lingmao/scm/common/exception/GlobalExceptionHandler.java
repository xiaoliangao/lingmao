package com.lingmao.scm.common.exception;

import com.lingmao.scm.common.result.R;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.BindException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.stream.Collectors;

/**
 * 全局异常处理：Controller 里永远不写 try/catch，异常一路抛到这里统一变成 R。
 * 为什么：错误处理只写一遍；业务异常、参数校验失败、未知异常各自有固定的 code 和日志级别。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 业务异常：预期内的失败，记 warn，msg 直接给前端看 */
    @ExceptionHandler(BizException.class)
    public R<Void> handleBiz(BizException e) {
        log.warn("业务异常: {}", e.getMessage());
        return R.fail(e.getCode(), e.getMessage());
    }

    /** 参数校验失败：@Valid 没过。MethodArgumentNotValidException 是 BindException 的子类，这里一起接住 */
    @ExceptionHandler(BindException.class)
    public R<Void> handleValidation(BindException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + " " + f.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return R.fail(400, msg);
    }

    /** 访问了不存在的路径 */
    @ExceptionHandler(NoResourceFoundException.class)
    public R<Void> handleNotFound(NoResourceFoundException e) {
        return R.fail(404, "接口不存在: " + e.getResourcePath());
    }

    /** 兜底：程序错误（空指针、SQL 错误……）。打完整堆栈到日志，但不把细节暴露给前端 */
    @ExceptionHandler(Exception.class)
    public R<Void> handleOther(Exception e) {
        log.error("未处理异常", e);
        return R.fail(500, "服务器内部错误，请查看日志");
    }
}
