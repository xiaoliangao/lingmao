package com.lingmao.scm.common.exception;

import com.lingmao.scm.common.log.TraceIdFilter;
import com.lingmao.scm.common.mybatis.TableComments;
import com.lingmao.scm.common.result.R;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.dao.DataIntegrityViolationException;
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
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import tools.jackson.core.JacksonException;

import java.sql.SQLException;
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
@RequiredArgsConstructor
public class GlobalExceptionHandler {

    /** PostgreSQL 唯一约束冲突的报错里，约束名在第一对双引号里：violates unique constraint "uk_xxx"（中文报错也一样） */
    private static final Pattern QUOTED = Pattern.compile("\"([^\"]+)\"");

    /** PostgreSQL 的 SQLState：外键冲突 */
    private static final String FOREIGN_KEY_VIOLATION = "23503";
    /** PostgreSQL 判了死锁、回滚了其中一个事务 */
    private static final String DEADLOCK = "40P01";

    /*
     * 外键冲突的报错长这样（PgJDBC 把 detail 接在消息后面；PostgreSQL 的报错是英文的，部署包和本机开发库都没改 lc_messages）：
     *   删除被拦住：ERROR: update or delete on table "sup_supplier" violates foreign key constraint "fk_x" on table "pur_order"
     *                Detail: Key (id)=(1) is still referenced from table "pur_order".
     *   引用的不存在：ERROR: insert or update on table "pur_order" violates foreign key constraint "fk_x"
     *                Detail: Key (supplier_id)=(9) is not present in table "sup_supplier".
     * 引用方的表名先从 detail 取，取不到（连接参数关了 detail）再从第一行取
     */
    private static final Pattern FK_STILL_REFERENCED = Pattern.compile("is still referenced from table \"([^\"]+)\"");
    private static final Pattern FK_REFERENCED = Pattern.compile(
            "update or delete on table \"[^\"]+\" violates foreign key constraint \"[^\"]+\" on table \"([^\"]+)\"");
    private static final Pattern FK_NOT_PRESENT = Pattern.compile("is not present in table \"[^\"]+\"");
    private static final Pattern FK_MISSING = Pattern.compile(
            "insert or update on table \"[^\"]+\" violates foreign key constraint \"[^\"]+\"");

    /**
     * 查表的中文名（表注释）用。用 ObjectProvider 取：@WebMvcTest 这类只起 Web 层的测试里没有数据库，
     * 取不到就用通用的说法，不至于让每个接口测试都去造一个假的
     */
    private final ObjectProvider<TableComments> tableComments;

    /** 业务异常：预期内的失败，记 warn，msg 直接给前端看；导入错误清单这类附加数据放在 data 里 */
    @ExceptionHandler(BizException.class)
    public R<Object> handleBiz(BizException e) {
        log.warn("业务异常 code={} type={}", e.getCode(), e.getClass().getSimpleName());
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

    /**
     * 上传请求本身坏了：传到一半断了、请求体不完整，或者根本不是 multipart。Tomcat 解析不了，Spring 包成这个（RFC 001 §18）。
     * 文件太大（MaxUploadSizeExceededException）是它的子类，由下面那个更具体的方法接
     */
    @ExceptionHandler(MultipartException.class)
    public R<Void> handleMultipart(MultipartException e) {
        log.warn("上传请求解析失败: {}", e.getClass().getSimpleName());
        return R.fail(400, "上传失败：请求不完整或格式不对，请重新上传");
    }

    /** 上传的文件超过大小限制（application.yaml 里的 spring.servlet.multipart，100MB；按类型的 10MB 在 FileServiceImpl） */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public R<Void> handleMaxUploadSize(MaxUploadSizeExceededException e) {
        return R.fail(400, "文件太大：AI、PSD 最大 100MB，其他文件最大 10MB");
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
     * 数据库约束拦住了（唯一冲突是它的子类，由上面的方法接）。只认外键冲突：
     * - 删除被引用的数据：新业务表不用逻辑删除，没被引用的直接删，被引用的由外键（RESTRICT）拦住，
     *   "用过的只能停用"靠数据库保证，不靠代码去查（用户 11-15 定）。返回 1005，说清是被什么引用了：
     *   报错里有引用方的表名，用表注释翻成中文；没写注释就说"其他数据"。
     * - 插入、修改时引用的数据不存在（页面开着的时候别人把它删了）：返回 1000 让用户刷新。
     * 非空、检查约束这些说明少了一处校验，是程序错误，和兜底一样返回 500
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public R<Void> handleDataIntegrity(DataIntegrityViolationException e, HttpServletRequest request) {
        SQLException sql = sqlExceptionOf(e);
        if (sql == null || !FOREIGN_KEY_VIOLATION.equals(sql.getSQLState())) {
            return handleOther(e, request);
        }
        String msg = Objects.requireNonNullElse(sql.getMessage(), "");
        // 报错原文（SQL、表名、键值）只进日志，不给前端
        if (FK_NOT_PRESENT.matcher(msg).find() || FK_MISSING.matcher(msg).find()) {
            log.warn("外键冲突，引用的数据不存在");
            return R.fail(ErrorCodes.BIZ_ERROR, "关联的数据不存在，可能刚被别人删除了，请刷新后再试");
        }
        // 认不出是哪种（比如数据库换成了中文报错），按删除处理：新表的外键冲突绝大多数是删除被拦住
        String table = firstGroup(msg, FK_STILL_REFERENCED, FK_REFERENCED);
        log.warn("删除被外键拦住，引用方 {}", table);
        TableComments comments = table == null ? null : tableComments.getIfAvailable();
        String name = comments == null ? null : comments.of(table);
        return R.fail(ErrorCodes.HAS_DOWNSTREAM,
                (name == null ? "已被其他数据引用" : "已被" + name + "引用") + "，不能删除，只能停用");
    }

    /**
     * 兜底：程序错误（空指针、SQL 错误、非空约束……）。完整堆栈打到日志，细节不给前端，
     * 只给错误编号：用户把编号发过来，grep 一下就能找到这次请求的全部日志
     */
    @ExceptionHandler(Exception.class)
    public R<Void> handleOther(Exception e, HttpServletRequest request) {
        SQLException sql = sqlExceptionOf(e);
        if (sql != null && "P2019".equals(sql.getSQLState())) {
            return R.fail(ErrorCodes.STATE_CHANGED, "上传文件尚未完成或已清理，请重新上传后保存");
        }
        if (hasSqlState(e, DEADLOCK)) {
            // 加锁都按 id 顺序（RFC 014 §6），万一还是被数据库判了死锁：这个事务已经整个回滚，让用户再点一次
            log.warn("数据库判了死锁，已回滚: {} {}", request.getMethod(), request.getRequestURI());
            return R.fail(ErrorCodes.STATE_CHANGED, "有人同时在改，请重试");
        }
        log.error("未处理异常: {} {} {}", request.getMethod(), request.getRequestURI(), com.lingmao.scm.common.log.SafeLog.failure(e));
        return R.fail(500, TraceIdFilter.serverErrorMsg());
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

    /** 按顺序试几个正则，返回第一个匹配上的第 1 组；都没匹配上返回 null */
    private static String firstGroup(String text, Pattern... patterns) {
        for (Pattern pattern : patterns) {
            Matcher matcher = pattern.matcher(text);
            if (matcher.find()) {
                return matcher.group(1);
            }
        }
        return null;
    }

    /** 顺着 cause 往下找驱动抛的 SQLException：MyBatis、Spring 会包好几层 */
    /** 报错链里有没有这个 SQLState：沿 cause 找，也看 SQLException 的 nextException（批量执行时真正的错误挂在那里） */
    private static boolean hasSqlState(Throwable e, String state) {
        java.util.Set<Throwable> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        java.util.List<Throwable> todo = new java.util.ArrayList<>();
        todo.add(e);
        while (!todo.isEmpty()) {
            Throwable t = todo.removeLast();
            if (t == null || !seen.add(t)) {
                continue;
            }
            if (t instanceof SQLException sql) {
                if (state.equals(sql.getSQLState())) {
                    return true;
                }
                todo.add(sql.getNextException());
            }
            todo.add(t.getCause());
        }
        return false;
    }

    private static SQLException sqlExceptionOf(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql) {
                return sql;
            }
        }
        return null;
    }

    /** 从数据库的报错里取约束名；取不到就把整条报错记下 */
    private static String constraintName(DuplicateKeyException e) {
        String msg = e.getMostSpecificCause().getMessage();
        Matcher matcher = QUOTED.matcher(msg == null ? "" : msg);
        return matcher.find() ? matcher.group(1) : msg;
    }
}
