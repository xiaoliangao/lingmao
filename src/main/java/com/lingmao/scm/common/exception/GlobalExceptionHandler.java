package com.lingmao.scm.common.exception;

import com.lingmao.scm.common.log.TraceIdFilter;
import com.lingmao.scm.common.result.R;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import tools.jackson.core.JacksonException;

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 全局异常处理：Controller 里永远不写 try/catch，异常一路抛到这里统一变成 R。
 * 为什么：错误处理只写一遍；业务异常、参数校验失败、未知异常各自有固定的 code 和日志级别。
 * Spring MVC 自己抛的异常（JSON 读不出来、方法不对、缺参数……）每种都要有对应的方法，
 * 没有的话会落到最后的兜底变成 500：bug 003、006、007 就是这么来的。对照表见 RFC 001 §5.2。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** PostgreSQL 唯一约束冲突的报错里，约束名在第一对双引号里：violates unique constraint "uk_xxx"（中文报错也一样） */
    private static final Pattern QUOTED = Pattern.compile("\"([^\"]+)\"");

    /** 业务异常：预期内的失败，记 warn，msg 直接给前端看；导入错误清单这类附加数据放在 data 里 */
    @ExceptionHandler(BizException.class)
    public R<Object> handleBiz(BizException e) {
        log.warn("业务异常: {}", e.getMessage());
        return R.fail(e.getCode(), e.getMessage(), e.getData());
    }

    /**
     * 参数校验失败：@Valid 没过。MethodArgumentNotValidException（请求体）是 BindException 的子类，这里一起接住。
     * 只用注解上写的 message，不再在前面拼字段名：message 里已经说了是哪个字段，再拼就成了"size size 最多 500"（bug 004）。
     * 查询参数的类型不对（?page=abc）也走这里，它没有 message，单独给一句。
     */
    @ExceptionHandler(BindException.class)
    public R<Void> handleBind(BindException e) {
        return R.fail(400, joinMessages(e.getBindingResult().getAllErrors().stream()
                .map(error -> error instanceof FieldError field && field.isBindingFailure()
                        ? "参数 " + field.getField() + " 格式不对"
                        : error.getDefaultMessage())));
    }

    /** 校验注解直接写在 Controller 方法的参数上（如 @RequestParam @Min(1) int x）时 Spring 抛这个，消息取法同上 */
    @ExceptionHandler(HandlerMethodValidationException.class)
    public R<Void> handleMethodValidation(HandlerMethodValidationException e) {
        return R.fail(400, joinMessages(e.getAllErrors().stream().map(MessageSourceResolvable::getDefaultMessage)));
    }

    /** 请求体读不出来：不是合法的 JSON，或者某个字段的类型、时间格式不对（bug 003）。认得出是哪个字段就带上 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public R<Void> handleNotReadable(HttpMessageNotReadableException e) {
        String field = e.getCause() instanceof JacksonException jackson ? fieldPath(jackson) : "";
        return R.fail(400, field.isEmpty() ? "请求体格式不对" : "请求体格式不对：字段 " + field);
    }

    /** 路径或查询参数的类型不对，如 /api/users/abc */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public R<Void> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        return R.fail(400, "参数 " + e.getName() + " 格式不对");
    }

    /** 缺了必填的查询参数 */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public R<Void> handleMissingParam(MissingServletRequestParameterException e) {
        return R.fail(400, "缺少参数 " + e.getParameterName());
    }

    /** 上传接口没带文件（bug 007） */
    @ExceptionHandler(MissingServletRequestPartException.class)
    public R<Void> handleMissingPart(MissingServletRequestPartException e) {
        return R.fail(400, "缺少参数 " + e.getRequestPartName());
    }

    /** Content-Type 不对，比如收 JSON 的接口传了表单 */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public R<Void> handleMediaType(HttpMediaTypeNotSupportedException e) {
        return R.fail(400, "不支持的 Content-Type: " + e.getContentType());
    }

    /** 上传的文件超过大小限制（application.yaml 里的 spring.servlet.multipart.max-file-size） */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public R<Void> handleMaxUploadSize(MaxUploadSizeExceededException e) {
        return R.fail(400, "文件太大，最大 10MB");
    }

    /** 已登录，但没有这个接口的权限（@PreAuthorize 没通过） */
    @ExceptionHandler(AccessDeniedException.class)
    public R<Void> handleAccessDenied(AccessDeniedException e) {
        return R.fail(403, "没有权限");
    }

    /** 访问了不存在的路径 */
    @ExceptionHandler(NoResourceFoundException.class)
    public R<Void> handleNotFound(NoResourceFoundException e) {
        return R.fail(404, "接口不存在: " + e.getResourcePath());
    }

    /** 方法不对：只支持 GET 的接口用了 POST（bug 006） */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public R<Void> handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        return R.fail(405, "不支持 " + e.getMethod() + " 请求");
    }

    /**
     * 唯一索引拦住了：Service 查过重，但查完到插入之间别人刚好建了同样的编码。不是程序错误，提示用户刷新。
     * 日志里记约束名：看名字就知道是哪张表的哪个编码
     */
    @ExceptionHandler(DuplicateKeyException.class)
    public R<Void> handleDuplicateKey(DuplicateKeyException e) {
        log.warn("唯一约束冲突: {}", constraintName(e));
        return R.fail(ErrorCodes.BIZ_ERROR, "数据重复，可能别人刚建了同样的编码，请刷新后再试");
    }

    /**
     * 兜底：程序错误（空指针、SQL 错误；外键冲突也算，说明少了一处检查）。完整堆栈打到日志，细节不给前端，
     * 只给错误编号：用户把编号发过来，grep 一下就能找到这次请求的全部日志
     */
    @ExceptionHandler(Exception.class)
    public R<Void> handleOther(Exception e, HttpServletRequest request) {
        log.error("未处理异常: {} {}", request.getMethod(), request.getRequestURI(), e);
        String traceId = MDC.get(TraceIdFilter.MDC_KEY);
        return R.fail(500, traceId == null ? "服务器出错了" : "服务器出错了，错误编号 " + traceId);
    }

    /** 多条消息用"；"连起来，一模一样的只留一条 */
    private static String joinMessages(Stream<String> messages) {
        return messages.filter(Objects::nonNull).distinct().collect(Collectors.joining("；"));
    }

    /** Jackson 记下了出错的位置，拼成 items[0].qty 这样的路径。整个请求体都不是 JSON 时没有位置，返回空串 */
    private static String fieldPath(JacksonException e) {
        StringBuilder path = new StringBuilder();
        for (JacksonException.Reference ref : e.getPath()) {
            if (ref.getPropertyName() != null) {
                if (!path.isEmpty()) {
                    path.append('.');
                }
                path.append(ref.getPropertyName());
            } else if (ref.getIndex() >= 0) {
                path.append('[').append(ref.getIndex()).append(']');
            }
        }
        return path.toString();
    }

    /** 从数据库的报错里取约束名；取不到就把整条报错记下 */
    private static String constraintName(DuplicateKeyException e) {
        String msg = e.getMostSpecificCause().getMessage();
        Matcher matcher = QUOTED.matcher(msg == null ? "" : msg);
        return matcher.find() ? matcher.group(1) : msg;
    }
}
