package com.lingmao.scm.module.basic.controller;

import com.lingmao.scm.common.log.OperLog;
import com.lingmao.scm.common.result.R;
import com.lingmao.scm.module.basic.dto.SizeAppendReq;
import com.lingmao.scm.module.basic.dto.SizeGroupCreateReq;
import com.lingmao.scm.module.basic.dto.SizeGroupRenameReq;
import com.lingmao.scm.module.basic.dto.SizeGroupVO;
import com.lingmao.scm.module.basic.service.SizeGroupService;
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

@Tag(name = "尺码组")
@RestController
@RequestMapping("/api/size-groups")
@RequiredArgsConstructor
public class SizeGroupController {

    private final SizeGroupService sizeGroupService;

    @Operation(summary = "全部（停用的也在），带尺码")
    @GetMapping
    @PreAuthorize("hasAuthority('basic:basics:list')")
    public R<List<SizeGroupVO>> list() {
        return R.ok(sizeGroupService.list());
    }

    @Operation(summary = "下拉：启用的，带尺码和基码（登录即可）")
    @GetMapping("/options")
    public R<List<SizeGroupVO>> options() {
        return R.ok(sizeGroupService.options());
    }

    @Operation(summary = "详情")
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('basic:basics:list')")
    public R<SizeGroupVO> get(@PathVariable("id") Long id) {
        return R.ok(sizeGroupService.getById(id));
    }

    @Operation(summary = "新增，返回新 id")
    @PostMapping
    @PreAuthorize("hasAuthority('basic:basics:add')")
    @OperLog(module = "尺码组", action = "新增")
    public R<Long> create(@Valid @RequestBody SizeGroupCreateReq req) {
        return R.ok(sizeGroupService.create(req));
    }

    @Operation(summary = "改名称（只能改名称）")
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('basic:basics:edit')")
    @OperLog(module = "尺码组", action = "修改")
    public R<Void> rename(@PathVariable("id") Long id, @Valid @RequestBody SizeGroupRenameReq req) {
        sizeGroupService.rename(id, req.getName());
        return R.ok();
    }

    @Operation(summary = "在末尾追加尺码")
    @PostMapping("/{id}/sizes")
    @PreAuthorize("hasAuthority('basic:basics:edit')")
    @OperLog(module = "尺码组", action = "追加尺码")
    public R<Void> appendSizes(@PathVariable("id") Long id, @Valid @RequestBody SizeAppendReq req) {
        sizeGroupService.appendSizes(id, req.getSizes());
        return R.ok();
    }

    @Operation(summary = "删除（连同尺码）")
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('basic:basics:remove')")
    @OperLog(module = "尺码组", action = "删除")
    public R<Void> delete(@PathVariable("id") Long id) {
        sizeGroupService.delete(id);
        return R.ok();
    }

    @Operation(summary = "启用")
    @PostMapping("/{id}/enable")
    @PreAuthorize("hasAuthority('basic:basics:edit')")
    @OperLog(module = "尺码组", action = "启用")
    public R<Void> enable(@PathVariable("id") Long id) {
        sizeGroupService.enable(id);
        return R.ok();
    }

    @Operation(summary = "停用")
    @PostMapping("/{id}/disable")
    @PreAuthorize("hasAuthority('basic:basics:edit')")
    @OperLog(module = "尺码组", action = "停用")
    public R<Void> disable(@PathVariable("id") Long id) {
        sizeGroupService.disable(id);
        return R.ok();
    }
}
