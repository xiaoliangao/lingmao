package com.lingmao.scm.module.system.aspect;

import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.common.log.OperLog;
import com.lingmao.scm.module.system.dto.UserCreateReq;
import com.lingmao.scm.module.system.entity.SysOperLog;
import com.lingmao.scm.module.system.mapper.SysOperLogMapper;
import com.lingmao.scm.module.system.mapper.SysUserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 不启动 Spring：用 AspectJProxyFactory 给一个演示用的 Controller 套上切面，直接调它 */
@ExtendWith(MockitoExtension.class)
class OperLogAspectTest {

    @Mock
    private SysOperLogMapper sysOperLogMapper;

    @Mock
    private SysUserMapper sysUserMapper;

    private DemoController proxy;

    @BeforeEach
    void setUp() {
        OperLogAspect aspect = new OperLogAspect(sysOperLogMapper, sysUserMapper, JsonMapper.builder().build());
        AspectJProxyFactory factory = new AspectJProxyFactory(new DemoController());
        factory.setProxyTargetClass(true);
        factory.addAspect(aspect);
        proxy = factory.getProxy();
    }

    /** 规则：成功的操作记一条 success=true 的日志，入参里的密码打码 */
    @Test
    void success_savesLogWithMaskedPassword() {
        Long id = proxy.create(req("zhangsan", "123456"));

        assertThat(id).isEqualTo(2L);
        ArgumentCaptor<SysOperLog> saved = ArgumentCaptor.forClass(SysOperLog.class);
        verify(sysOperLogMapper).insert(saved.capture());
        SysOperLog log = saved.getValue();
        assertThat(log.getModule()).isEqualTo("用户管理");
        assertThat(log.getAction()).isEqualTo("新增");
        assertThat(log.getMethod()).isEqualTo("DemoController.create");
        assertThat(log.getSuccess()).isTrue();
        assertThat(log.getParams()).contains("\"username\":\"zhangsan\"").contains("\"password\":\"******\"")
                .doesNotContain("123456");
    }

    /** 规则：业务失败也记，success=false，带上原因；异常照样抛给调用方 */
    @Test
    void failure_savesLogAndRethrows() {
        assertThatThrownBy(() -> proxy.fail())
                .isInstanceOf(BizException.class)
                .hasMessage("用户名已存在: zhangsan");

        ArgumentCaptor<SysOperLog> saved = ArgumentCaptor.forClass(SysOperLog.class);
        verify(sysOperLogMapper).insert(saved.capture());
        assertThat(saved.getValue().getSuccess()).isFalse();
        assertThat(saved.getValue().getErrorMsg()).isEqualTo("用户名已存在: zhangsan");
    }

    /** 规则：写日志失败（比如库连不上）不能连累业务 */
    @Test
    void logInsertFails_businessResultStillReturned() {
        when(sysOperLogMapper.insert(any(SysOperLog.class))).thenThrow(new RuntimeException("数据库连不上"));

        assertThat(proxy.create(req("zhangsan", "123456"))).isEqualTo(2L);
    }

    private UserCreateReq req(String username, String password) {
        UserCreateReq req = new UserCreateReq();
        req.setUsername(username);
        req.setPassword(password);
        req.setNickname("张三");
        return req;
    }

    /** 演示用的 Controller：只为了让切面有东西可切 */
    static class DemoController {

        @OperLog(module = "用户管理", action = "新增")
        public Long create(UserCreateReq req) {
            return 2L;
        }

        @OperLog(module = "用户管理", action = "新增")
        public Long fail() {
            throw new BizException("用户名已存在: zhangsan");
        }
    }
}
