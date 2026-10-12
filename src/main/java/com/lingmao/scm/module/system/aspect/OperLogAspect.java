package com.lingmao.scm.module.system.aspect;

import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.common.log.OperLog;
import com.lingmao.scm.module.system.entity.SysOperLog;
import com.lingmao.scm.module.system.entity.SysUser;
import com.lingmao.scm.module.system.mapper.SysOperLogMapper;
import com.lingmao.scm.module.system.mapper.SysUserMapper;
import com.lingmao.scm.security.LoginUser;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 操作日志切面：所有贴了 @OperLog 的方法，执行前后由这里统一记一条日志。
 * 业务代码里一行日志代码都不用写；写日志失败也不影响业务（只打一条 warn）。
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class OperLogAspect {

    private static final int MAX_PARAMS_LENGTH = 2000;
    private static final int MAX_ERROR_LENGTH = 500;

    private final SysOperLogMapper sysOperLogMapper;
    private final SysUserMapper sysUserMapper;
    private final JsonMapper jsonMapper;

    /** 环绕：先记下开始时间，执行真正的方法，不管成功还是抛异常，最后都写一条日志 */
    @Around("@annotation(operLog)")
    public Object around(ProceedingJoinPoint joinPoint, OperLog operLog) throws Throwable {
        long start = System.currentTimeMillis();
        Throwable error = null;
        try {
            return joinPoint.proceed();
        } catch (Throwable t) {
            error = t;
            throw t;
        } finally {
            save(joinPoint, operLog, error, System.currentTimeMillis() - start);
        }
    }

    private void save(ProceedingJoinPoint joinPoint, OperLog operLog, Throwable error, long costMs) {
        try {
            MethodSignature signature = (MethodSignature) joinPoint.getSignature();
            SysOperLog record = new SysOperLog();
            record.setModule(operLog.module());
            record.setAction(operLog.action());
            record.setMethod(signature.getDeclaringType().getSimpleName() + "." + signature.getName());
            record.setParams(toParamsJson(signature.getParameterNames(), joinPoint.getArgs()));
            record.setSuccess(error == null);
            record.setErrorMsg(error == null ? null : errorMessage(error));
            record.setCostMs((int) costMs);
            fillUser(record);
            fillRequest(record);
            sysOperLogMapper.insert(record);
        } catch (Exception e) {
            log.warn("操作日志写入失败: {}.{}", operLog.module(), operLog.action(), e);
        }
    }

    /** 当前登录的人。登录接口本身还没认出人，这两列就空着 */
    private void fillUser(SysOperLog record) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof LoginUser loginUser) {
            record.setUserId(loginUser.userId());
            SysUser user = sysUserMapper.selectById(loginUser.userId());
            record.setUsername(user == null ? null : user.getUsername());
        }
    }

    private void fillRequest(SysOperLog record) {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs)) {
            return;
        }
        HttpServletRequest request = attrs.getRequest();
        record.setRequestMethod(request.getMethod());
        record.setRequestUri(request.getRequestURI());
        String forwarded = request.getHeader("X-Forwarded-For");
        record.setIp(forwarded != null ? forwarded.split(",")[0].trim() : request.getRemoteAddr());
    }

    /** 参数名 → 参数值，转成 JSON；请求/响应对象、上传的文件、当前用户不记；密码打码；太长截断 */
    String toParamsJson(String[] names, Object[] args) {
        Map<String, Object> params = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) {
            Object arg = args[i];
            if (arg instanceof ServletRequest || arg instanceof ServletResponse
                    || arg instanceof MultipartFile || arg instanceof LoginUser) {
                continue;
            }
            params.put(names[i], arg);
        }
        JsonNode tree = jsonMapper.valueToTree(params);
        maskPasswords(tree);
        String json = jsonMapper.writeValueAsString(tree);
        return json.length() > MAX_PARAMS_LENGTH ? json.substring(0, MAX_PARAMS_LENGTH) + "…" : json;
    }

    /** 递归找名字叫 password 的字段，值换成 ****** */
    private void maskPasswords(JsonNode node) {
        if (node instanceof ObjectNode object) {
            List<String> names = new ArrayList<>();
            object.properties().forEach(entry -> names.add(entry.getKey()));
            for (String name : names) {
                if ("password".equalsIgnoreCase(name)) {
                    object.put(name, "******");
                } else {
                    maskPasswords(object.get(name));
                }
            }
        } else if (node != null && node.isArray()) {
            node.forEach(this::maskPasswords);
        }
    }

    private String errorMessage(Throwable error) {
        String msg = error instanceof BizException ? error.getMessage()
                : error.getClass().getSimpleName() + ": " + error.getMessage();
        return msg != null && msg.length() > MAX_ERROR_LENGTH ? msg.substring(0, MAX_ERROR_LENGTH) : msg;
    }
}
