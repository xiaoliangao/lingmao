package com.lingmao.scm.module.biz.controller;

import com.lingmao.scm.common.log.OperLog;
import com.lingmao.scm.common.result.R;
import com.lingmao.scm.module.biz.dto.ApprovalRuleSaveReq;
import com.lingmao.scm.module.biz.dto.ApprovalRuleVO;
import com.lingmao.scm.module.biz.service.ApprovalRuleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 设置 › 审批（docs/api/README §4.6、架构 §4.8）：多级审批的规则。管理后台的页面，两个权限码是 admin_only 的菜单上的，
 * 只有超级管理员拿得到（V17）。规则不多，不分页
 */
@Tag(name = "审批规则")
@RestController
@RequestMapping("/api/approval-rules")
@RequiredArgsConstructor
public class ApprovalRuleController {

    private final ApprovalRuleService approvalRuleService;

    @Operation(summary = "全部规则，按单据类型、门槛从低到高")
    @GetMapping
    @PreAuthorize("hasAuthority('biz:approval-rule:list')")
    public R<List<ApprovalRuleVO>> list() {
        return R.ok(approvalRuleService.list());
    }

    @Operation(summary = "新增，返回新 id")
    @PostMapping
    @PreAuthorize("hasAuthority('biz:approval-rule:edit')")
    @OperLog(module = "审批规则", action = "新增")
    public R<Long> create(@Valid @RequestBody ApprovalRuleSaveReq req) {
        return R.ok(approvalRuleService.create(req));
    }

    @Operation(summary = "修改（整体覆盖）")
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('biz:approval-rule:edit')")
    @OperLog(module = "审批规则", action = "修改")
    public R<Void> update(@PathVariable("id") Long id, @Valid @RequestBody ApprovalRuleSaveReq req) {
        approvalRuleService.update(id, req);
        return R.ok();
    }

    @Operation(summary = "删除：已经提交的单不受影响")
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('biz:approval-rule:edit')")
    @OperLog(module = "审批规则", action = "删除")
    public R<Void> delete(@PathVariable("id") Long id) {
        approvalRuleService.delete(id);
        return R.ok();
    }
}
