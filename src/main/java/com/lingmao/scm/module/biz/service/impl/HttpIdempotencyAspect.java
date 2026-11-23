package com.lingmao.scm.module.biz.service.impl;

import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.common.exception.ErrorCodes;
import com.lingmao.scm.common.idempotency.IdempotencyInProgress;
import com.lingmao.scm.common.idempotency.IdempotencyReplayGuard;
import com.lingmao.scm.common.idempotency.IdempotentCreate;
import com.lingmao.scm.common.idempotency.RequestFingerprint;
import com.lingmao.scm.common.result.R;
import com.lingmao.scm.module.biz.mapper.HttpActionRequestMapper;
import com.lingmao.scm.module.biz.mapper.HttpActionRequestMapper.Scope;
import com.lingmao.scm.security.LoginUser;
import jakarta.servlet.http.HttpServletRequest;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.lang.reflect.ParameterizedType;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;

/**
 * 方法权限检查（order 200）之后才认领，事务内包含认领、业务落库和最小结果，失败一起回滚。
 * advisory try-lock 使正在处理的同键立即 409；进程退出/连接丢失由数据库自动释放，不留永久 PROCESSING。
 */
@Aspect
@Component
@Order(500)
public final class HttpIdempotencyAspect {
    private final HttpActionRequestMapper requests;
    private final JsonMapper json;
    private final TransactionTemplate tx;
    private final String tenant;

    public HttpIdempotencyAspect(HttpActionRequestMapper requests, JsonMapper json, PlatformTransactionManager manager,
                                @Value("${lingmao.tenant-id:single}") String tenant) {
        if (!tenant.matches("[A-Za-z0-9_-]{1,64}")) throw new IllegalArgumentException("tenant-id 格式不正确");
        this.requests = requests;
        this.json = json;
        this.tenant = tenant;
        this.tx = new TransactionTemplate(manager);
        this.tx.setTimeout(30);
    }

    @Around("@annotation(marker)")
    public Object execute(ProceedingJoinPoint call, IdempotentCreate marker) throws Throwable {
        Long userId = LoginUser.currentUserId();
        if (userId == null) throw new BizException(401, "请先登录");
        if (!(call.getTarget() instanceof IdempotencyReplayGuard guard)) {
            throw new IllegalStateException("幂等建单必须提供重放授权");
        }
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs)) {
            throw new IllegalStateException("幂等 HTTP 入口只能通过请求调用");
        }
        HttpServletRequest request = attrs.getRequest();
        UUID key = keyOf(request.getHeader("Idempotency-Key"));
        Object[] args = call.getArgs();
        Long company = companyOf(request, args, marker.companyArgumentIndex());
        Scope scope = new Scope(tenant, userId, company, marker.value(), key);
        String hash = RequestFingerprint.of(json, Map.of("operation", marker.value(), "company", company == null ? "" : company.toString(), "arguments", Arrays.asList(args)));
        long lockKey = RequestFingerprint.lockKey("HTTP\n" + tenant + "\n" + userId + "\n" + marker.value() + "\n" + key);
        JavaType dataType = json.constructType(((ParameterizedType) ((MethodSignature) call.getSignature())
                .getMethod().getGenericReturnType()).getActualTypeArguments()[0]);
        try {
            return tx.execute(status -> {
                if (!requests.tryLock(lockKey)) throw new IdempotencyInProgress();
                var stored = requests.find(scope);
                if (stored != null) {
                    if (!hash.equals(stored.requestHash())) throw new BizException(ErrorCodes.STATE_CHANGED,
                            "同一次操作的内容已改变，请先核对前次结果，再开始新操作");
                    if (!"COMPLETED".equals(stored.state()) || stored.responseJson() == null) throw new IdempotencyInProgress();
                    JsonNode body = json.readTree(stored.responseJson());
                    guard.authorizeReplay(marker.value(), args, body.get("data"));
                    return R.ok(json.convertValue(body.get("data"), dataType));
                }
                requests.claim(scope, hash);
                Object result;
                try {
                    result = call.proceed();
                } catch (Throwable failure) {
                    throw new InvocationFailed(failure);
                }
                if (!(result instanceof R<?> envelope) || envelope.getCode() != 0) {
                    throw new IllegalStateException("幂等入口只缓存成功的 R 返回体");
                }
                String encoded = json.writeValueAsString(result);
                if (encoded.length() > 1_048_576) throw new BizException(400, "本次生成结果过多，请分批操作");
                if (requests.complete(scope, encoded) != 1) throw new IllegalStateException("幂等结果状态不一致");
                return result;
            });
        } catch (InvocationFailed failure) {
            throw failure.getCause();
        }
    }

    private Long companyOf(HttpServletRequest request, Object[] args, int companyArgumentIndex) {
        Long expectedCompany = null;
        if (companyArgumentIndex < -1 || companyArgumentIndex >= args.length) {
            throw new IllegalStateException("Invalid original-company controller argument index");
        }
        if (companyArgumentIndex >= 0) {
            if (!(args[companyArgumentIndex] instanceof Long originalCompany)) {
                throw new IllegalStateException("Original-company controller argument must be a Long");
            }
            if (originalCompany <= 0) throw new BizException(400, "原主体编号不合法");
            expectedCompany = originalCompany;
        }
        Long bodyCompany = null;
        for (Object argument : args) {
            JsonNode node = json.valueToTree(argument);
            if (node != null && node.isObject() && node.hasNonNull("companyId")) bodyCompany = node.get("companyId").longValue();
        }
        String header = request.getHeader("X-Company-Id");
        if (header == null) {
            if (expectedCompany != null) throw new BizException(400, "缺少主体请求头");
            return bodyCompany;
        }
        Long company;
        try {
            if (!header.matches("[1-9][0-9]{0,18}")) throw new NumberFormatException();
            company = Long.valueOf(header);
        } catch (NumberFormatException failure) {
            throw new BizException(400, "主体请求头格式不正确");
        }
        Long boundCompany = expectedCompany == null ? bodyCompany : expectedCompany;
        if (boundCompany != null && !boundCompany.equals(company)) throw new BizException(409, "页面主体已变化，请核对后重试");
        return company;
    }

    private static UUID keyOf(String value) {
        try {
            if (value == null || value.length() != 36) throw new IllegalArgumentException();
            UUID key = UUID.fromString(value);
            if (!key.toString().equalsIgnoreCase(value)) throw new IllegalArgumentException();
            return key;
        } catch (IllegalArgumentException failure) {
            throw new BizException(400, "缺少或无效的 Idempotency-Key，请刷新后重试");
        }
    }

    private static final class InvocationFailed extends RuntimeException {
        private InvocationFailed(Throwable cause) { super(cause); }
    }
}
