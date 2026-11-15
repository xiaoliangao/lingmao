package com.lingmao.scm.module.basic.controller;

import com.lingmao.scm.common.log.OperLog;
import com.lingmao.scm.common.result.R;
import com.lingmao.scm.module.basic.dto.CompanyOption;
import com.lingmao.scm.module.basic.dto.CompanySaveReq;
import com.lingmao.scm.module.basic.entity.Company;
import com.lingmao.scm.module.basic.service.CompanyService;
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

@Tag(name = "主体公司")
@RestController
@RequestMapping("/api/companies")
@RequiredArgsConstructor
public class CompanyController {

    private final CompanyService companyService;

    @Operation(summary = "全部（停用的也在）")
    @GetMapping
    @PreAuthorize("hasAuthority('basic:basics:list')")
    public R<List<Company>> list() {
        return R.ok(companyService.list());
    }

    @Operation(summary = "下拉：启用的（登录即可）")
    @GetMapping("/options")
    public R<List<CompanyOption>> options() {
        return R.ok(companyService.options());
    }

    @Operation(summary = "详情")
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('basic:basics:list')")
    public R<Company> get(@PathVariable("id") Long id) {
        return R.ok(companyService.getById(id));
    }

    @Operation(summary = "新增，返回新 id")
    @PostMapping
    @PreAuthorize("hasAuthority('basic:basics:add')")
    @OperLog(module = "主体公司", action = "新增")
    public R<Long> create(@Valid @RequestBody CompanySaveReq req) {
        return R.ok(companyService.create(req));
    }

    @Operation(summary = "修改（编码不能改）")
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('basic:basics:edit')")
    @OperLog(module = "主体公司", action = "修改")
    public R<Void> update(@PathVariable("id") Long id, @Valid @RequestBody CompanySaveReq req) {
        companyService.update(id, req);
        return R.ok();
    }

    @Operation(summary = "删除")
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('basic:basics:remove')")
    @OperLog(module = "主体公司", action = "删除")
    public R<Void> delete(@PathVariable("id") Long id) {
        companyService.delete(id);
        return R.ok();
    }

    @Operation(summary = "启用")
    @PostMapping("/{id}/enable")
    @PreAuthorize("hasAuthority('basic:basics:edit')")
    @OperLog(module = "主体公司", action = "启用")
    public R<Void> enable(@PathVariable("id") Long id) {
        companyService.enable(id);
        return R.ok();
    }

    @Operation(summary = "停用（最后一个启用的不能停）")
    @PostMapping("/{id}/disable")
    @PreAuthorize("hasAuthority('basic:basics:edit')")
    @OperLog(module = "主体公司", action = "停用")
    public R<Void> disable(@PathVariable("id") Long id) {
        companyService.disable(id);
        return R.ok();
    }
}
