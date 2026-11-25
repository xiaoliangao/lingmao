package com.lingmao.scm.module.system.controller;

import com.lingmao.scm.common.log.OperLog;
import com.lingmao.scm.common.result.R;
import com.lingmao.scm.module.system.dto.CodeRuleSaveReq;
import com.lingmao.scm.module.system.dto.CodeRuleVO;
import com.lingmao.scm.module.system.dto.CodeTarget;
import com.lingmao.scm.module.system.service.CodeRuleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 编码规则（RFC 018 §4）：管理后台 › 系统管理 › 编码规则，只给超级管理员 */
@Tag(name = "编码规则")
@RestController
@RequestMapping("/api/code-rules")
@RequiredArgsConstructor
public class CodeRuleController {

    private final CodeRuleService codeRuleService;

    @Operation(summary = "一种编码的全部规则：通用规则在最前")
    @GetMapping
    @PreAuthorize("@superAdmin.check(authentication)")
    public R<List<CodeRuleVO>> list(@Parameter(description = "哪种编码：STYLE 款号 / MATERIAL 物料编码 / SUPPLIER 供应商编码")
                                    @RequestParam("target") CodeTarget target) {
        return R.ok(codeRuleService.list(target));
    }

    @Operation(summary = "新建规则（按大类 / 物料类型分的），返回新 id；只管以后新编的")
    @PostMapping
    @PreAuthorize("@superAdmin.check(authentication)")
    @OperLog(module = "编码规则", action = "新增")
    public R<Long> create(@Valid @RequestBody CodeRuleSaveReq req) {
        return R.ok(codeRuleService.create(req));
    }

    @Operation(summary = "修改规则：只管以后新编的，已有的编码不变")
    @PutMapping("/{id}")
    @PreAuthorize("@superAdmin.check(authentication)")
    @OperLog(module = "编码规则", action = "修改")
    public R<Void> update(@PathVariable("id") Long id, @Valid @RequestBody CodeRuleSaveReq req) {
        codeRuleService.update(id, req);
        return R.ok();
    }

    @Operation(summary = "删除规则：通用规则不能删；删了以后这些大类 / 物料类型按通用规则编")
    @DeleteMapping("/{id}")
    @PreAuthorize("@superAdmin.check(authentication)")
    @OperLog(module = "编码规则", action = "删除")
    public R<Void> delete(@PathVariable("id") Long id) {
        codeRuleService.delete(id);
        return R.ok();
    }
}
