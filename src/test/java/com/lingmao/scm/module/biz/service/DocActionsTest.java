package com.lingmao.scm.module.biz.service;

import com.lingmao.scm.common.doc.DocStatus;
import com.lingmao.scm.common.doc.StatusFlow;
import com.lingmao.scm.module.biz.dto.ApprovalView;
import com.lingmao.scm.security.LoginUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Arrays;
import java.util.List;

import static com.lingmao.scm.common.doc.DocStatus.APPROVED;
import static com.lingmao.scm.common.doc.DocStatus.CLOSED;
import static com.lingmao.scm.common.doc.DocStatus.DONE;
import static com.lingmao.scm.common.doc.DocStatus.DRAFT;
import static com.lingmao.scm.common.doc.DocStatus.PENDING;
import static com.lingmao.scm.common.doc.DocStatus.RUNNING;
import static com.lingmao.scm.common.doc.DocStatus.VOIDED;
import static org.assertj.core.api.Assertions.assertThat;

/** 按钮由后端算（架构 §4.2）：状态允许 ∩ 有权限 ∩ 业务条件；多级审批的 audit、reject 只看是不是当前级的审批人 */
class DocActionsTest {

    private static final StatusFlow<DocStatus> FLOW = StatusFlow.of(DocStatus.class)
            .on("submit", DRAFT).to(PENDING)
            .on("withdraw", PENDING).to(DRAFT)
            .on("reject", PENDING).to(DRAFT)
            .on("audit", PENDING).to(APPROVED)
            .on("unaudit", APPROVED).to(DRAFT)
            .on("start", APPROVED).to(RUNNING)
            .on("finish", RUNNING).to(DONE)
            .on("close", APPROVED, RUNNING).to(CLOSED)
            .on("void", DRAFT, PENDING).to(VOIDED)
            .build();

    @AfterEach
    void logout() {
        SecurityContextHolder.clearContext();
    }

    /** 规则：没权限的不出；系统动作（start）不出；业务条件不满足的不出（有下游不出反审核） */
    @Test
    void permAndCondition() {
        login("purchase:order:audit", "purchase:order:close");

        assertThat(DocActions.of(FLOW, APPROVED, "purchase:order:").when("unaudit", false).list()).containsExactly("close");
        assertThat(DocActions.of(FLOW, APPROVED, "purchase:order:").list()).containsExactly("unaudit", "close");
    }

    /** 规则：多级审批的单据，audit、reject 只给当前这一级的审批人，有审核权限码也不算；撤回照样看 submit 权限 */
    @Test
    void approval_onlyCurrentApprover() {
        login("purchase:order:audit", "purchase:order:submit");
        ApprovalView notMyLevel = new ApprovalView(1, List.of(), "第 2 级 · 总经理", false);
        ApprovalView myLevel = new ApprovalView(1, List.of(), "第 2 级 · 总经理", true);

        assertThat(DocActions.of(FLOW, PENDING, "purchase:order:").approval(notMyLevel).list()).containsExactly("withdraw");
        assertThat(DocActions.of(FLOW, PENDING, "purchase:order:").approval(myLevel).list()).containsExactly("withdraw", "reject", "audit");
    }

    private static void login(String... perms) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new LoginUser(1L, "jti"), null, Arrays.stream(perms).map(SimpleGrantedAuthority::new).toList()));
    }
}
