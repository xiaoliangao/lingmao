package com.lingmao.scm.module.biz.service.impl;

import com.lingmao.scm.IntegrationTest;
import com.lingmao.scm.common.doc.DocStatus;
import com.lingmao.scm.common.page.PageQuery;
import com.lingmao.scm.common.page.PageResult;
import com.lingmao.scm.module.biz.dto.DocLogVO;
import com.lingmao.scm.module.biz.entity.BizType;
import com.lingmao.scm.module.biz.service.DocLogService;
import com.lingmao.scm.security.LoginUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

/** 操作记录在真库上（V9）：新的在前、带人名（人删了也有）、分页、按类型 + id 一起删。每个测试用随机的 doc_id */
@IntegrationTest
class DocLogServiceImplIT {

    @Autowired
    private DocLogService docLogService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void logout() {
        SecurityContextHolder.clearContext();
    }

    /**
     * 规则：读出来新的在前，状态存的是枚举名，操作人带昵称；做过事的人后来被删了（逻辑删除），名字照样显示；
     * 系统做的（没有登录用户）名字是空。分页的总数对
     */
    @Test
    void list_newestFirst_withNamesEvenIfUserDeleted() {
        long docId = randomId();
        Long userId = jdbcTemplate.queryForObject("""
                INSERT INTO sys_user (username, password, nickname, deleted) VALUES (?, 'x', '离职的人', true) RETURNING id
                """, Long.class, "it_" + UUID.randomUUID().toString().substring(0, 8));
        try {
            login(userId);
            docLogService.add(BizType.SUPPLIER, docId, "create", null, DocStatus.DRAFT, null);
            docLogService.add(BizType.SUPPLIER, docId, "submit", DocStatus.DRAFT, DocStatus.PENDING, "申请准入");
            SecurityContextHolder.clearContext();
            docLogService.add(BizType.SUPPLIER, docId, "start", "第一次入库");

            List<DocLogVO> list = docLogService.list(BizType.SUPPLIER, docId);

            assertThat(list).extracting(DocLogVO::action).containsExactly("start", "submit", "create");
            assertThat(list).extracting(DocLogVO::operatorName).containsExactly(null, "离职的人", "离职的人");
            assertThat(list.get(1)).extracting(DocLogVO::fromStatus, DocLogVO::toStatus, DocLogVO::remark)
                    .containsExactly("DRAFT", "PENDING", "申请准入");
            assertThat(list).allSatisfy(log -> assertThat(log.createTime()).isNotNull());

            PageQuery query = new PageQuery();
            query.setSize(2);
            PageResult<DocLogVO> page = docLogService.page(BizType.SUPPLIER, docId, query);
            assertThat(page.getTotal()).isEqualTo(3);
            assertThat(page.getList()).extracting(DocLogVO::action).containsExactly("start", "submit");
        } finally {
            jdbcTemplate.update("DELETE FROM sys_user WHERE id = ?", userId);
        }
    }

    /** 规则：按类型 + id 只删这条记录的；别的记录、别的类型同一个 id 的不动 */
    @Test
    void deleteByDoc_onlyThisDoc() {
        long docId = randomId();
        long otherId = randomId();
        docLogService.add(BizType.STYLE, docId, "enable", "a");
        docLogService.add(BizType.STYLE, docId, "disable", "b");
        docLogService.add(BizType.STYLE, otherId, "enable", "c");
        docLogService.add(BizType.MATERIAL, docId, "enable", "d");

        docLogService.deleteByDoc(BizType.STYLE, docId);

        assertThat(docLogService.list(BizType.STYLE, docId)).isEmpty();
        assertThat(docLogService.list(BizType.STYLE, otherId)).hasSize(1);
        assertThat(docLogService.list(BizType.MATERIAL, docId)).hasSize(1);
    }

    private static void login(long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(new LoginUser(userId, "it"), null, List.of()));
    }

    private static long randomId() {
        return ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE);
    }
}
