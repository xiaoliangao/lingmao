package com.lingmao.scm.module.biz.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.lingmao.scm.common.doc.DocStatus;
import com.lingmao.scm.common.doc.FieldChange;
import com.lingmao.scm.module.biz.dto.DocLogVO;
import com.lingmao.scm.module.biz.dto.FieldChangeVO;
import com.lingmao.scm.module.biz.entity.BizType;
import com.lingmao.scm.module.biz.entity.DocLog;
import com.lingmao.scm.module.biz.mapper.DocLogMapper;
import com.lingmao.scm.module.system.service.UserService;
import com.lingmao.scm.security.FieldPermissions;
import com.lingmao.scm.security.LoginUser;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 操作记录（架构 §4.4）：写进去的是什么、读出来带不带人名 */
@ExtendWith(MockitoExtension.class)
class DocLogServiceImplTest {

    @Mock
    private DocLogMapper docLogMapper;

    @Mock
    private UserService userService;

    @InjectMocks
    private DocLogServiceImpl service;

    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), DocLog.class);
    }

    @AfterEach
    void logout() {
        SecurityContextHolder.clearContext();
    }

    /** 规则：状态动作记下类型、id、动作、前后状态的枚举名、备注；操作人是当前登录用户，调用方不用传 */
    @Test
    void add_statusAction_writesAllColumns() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(new LoginUser(5L, "jti"), null, List.of()));

        service.add(BizType.SUPPLIER, 9L, "reject", DocStatus.PENDING, DocStatus.DRAFT, "营业执照过期了");

        ArgumentCaptor<DocLog> log = ArgumentCaptor.captor();
        verify(docLogMapper).insert(log.capture());
        assertThat(log.getValue()).extracting(DocLog::getDocType, DocLog::getDocId, DocLog::getAction,
                        DocLog::getFromStatus, DocLog::getToStatus, DocLog::getRemark, DocLog::getOperatorId)
                .containsExactly(BizType.SUPPLIER, 9L, "reject", "PENDING", "DRAFT", "营业执照过期了", 5L);
    }

    /** 规则：状态没变的动作两个状态都空；没有登录用户（定时任务）操作人为空 */
    @Test
    void add_withoutStatusOrLogin_leavesThemNull() {
        service.add(BizType.STYLE, 3L, "export-pdf", "含价格");

        ArgumentCaptor<DocLog> log = ArgumentCaptor.captor();
        verify(docLogMapper).insert(log.capture());
        assertThat(log.getValue().getFromStatus()).isNull();
        assertThat(log.getValue().getToStatus()).isNull();
        assertThat(log.getValue().getOperatorId()).isNull();
    }

    /** 规则：读出来带操作人昵称，一次向 system 取齐（只取有操作人的 id）；系统做的那行名字是空 */
    @Test
    void list_fillsOperatorNames() {
        when(docLogMapper.selectList(any())).thenReturn(List.of(log(2L, 5L), log(1L, null)));
        when(userService.nicknames(Set.of(5L))).thenReturn(Map.of(5L, "张三"));

        List<DocLogVO> list = service.list(BizType.STYLE, 3L);

        assertThat(list).extracting(DocLogVO::id).containsExactly(2L, 1L);
        assertThat(list).extracting(DocLogVO::operatorName).containsExactly("张三", null);
    }

    /** 规则（RFC 016 §3）：改前改后存进 extra.diff，要权限的字段连权限名一起存 */
    @Test
    void add_diff_storedInExtra() {
        service.add(BizType.MATERIAL, 4L, "update", null, List.of(
                new FieldChange("name", "名称", "棉布", "麻布", null),
                new FieldChange("refPrice", "参考单价", "12", null, FieldPermissions.COST)));

        ArgumentCaptor<DocLog> log = ArgumentCaptor.captor();
        verify(docLogMapper).insert(log.capture());
        Map<String, Object> cleared = new HashMap<>(Map.of("field", "refPrice", "label", "参考单价", "before", "12",
                "permission", FieldPermissions.COST));
        cleared.put("after", null);
        assertThat(log.getValue().getExtra()).isEqualTo(Map.of("diff", List.of(
                Map.of("field", "name", "label", "名称", "before", "棉布", "after", "麻布"), cleared)));
    }

    /** 规则：读出来时，没有那个权限的人只看到"改了"（hidden，值是空）；有权限的看到值；没有 diff 的是空列表 */
    @Test
    void diffOf_masksByPermission() {
        Map<String, Object> extra = Map.of("diff", List.of(
                Map.of("field", "name", "label", "名称", "before", "棉布", "after", "麻布"),
                Map.of("field", "refPrice", "label", "参考单价", "before", "12", "after", "13", "permission", FieldPermissions.COST)));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(new LoginUser(5L, "jti"), null, List.of()));

        assertThat(DocLogServiceImpl.diffOf(extra)).containsExactly(
                new FieldChangeVO("name", "名称", "棉布", "麻布", false),
                new FieldChangeVO("refPrice", "参考单价", null, null, true));

        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new LoginUser(5L, "jti"), null, List.of(new SimpleGrantedAuthority(FieldPermissions.COST))));
        assertThat(DocLogServiceImpl.diffOf(extra)).last()
                .isEqualTo(new FieldChangeVO("refPrice", "参考单价", "12", "13", false));
        assertThat(DocLogServiceImpl.diffOf(null)).isEmpty();
    }

    private static DocLog log(Long id, Long operatorId) {
        DocLog log = new DocLog();
        log.setId(id);
        log.setAction("enable");
        log.setOperatorId(operatorId);
        return log;
    }
}
