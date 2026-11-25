package com.lingmao.scm.module.system.controller;

import com.lingmao.scm.common.log.OperLog;
import com.lingmao.scm.common.result.R;
import com.lingmao.scm.module.system.dto.DeptSaveReq;
import com.lingmao.scm.module.system.dto.DeptVO;
import com.lingmao.scm.module.system.service.DeptService;
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

/** 部门（RFC 018 §1.5）：在管理后台 › 用户页左边的树上维护，权限跟着用户管理走 */
@Tag(name = "部门")
@RestController
@RequestMapping("/api/depts")
@RequiredArgsConstructor
public class DeptController {

    private final DeptService deptService;

    @Operation(summary = "整棵部门树：每个部门带直属人数、全路径")
    @GetMapping
    @PreAuthorize("hasAuthority('system:user:list')")
    public R<List<DeptVO>> tree() {
        return R.ok(deptService.tree());
    }

    @Operation(summary = "新建部门，返回新 id；最多 5 级，同一上级下不重名")
    @PostMapping
    @PreAuthorize("hasAuthority('system:user:edit')")
    @OperLog(module = "部门", action = "新增")
    public R<Long> create(@Valid @RequestBody DeptSaveReq req) {
        return R.ok(deptService.create(req));
    }

    @Operation(summary = "改名、换上级、排序；不能换到自己或自己的下级下面")
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('system:user:edit')")
    @OperLog(module = "部门", action = "修改")
    public R<Void> update(@PathVariable("id") Long id, @Valid @RequestBody DeptSaveReq req) {
        deptService.update(id, req);
        return R.ok();
    }

    @Operation(summary = "删除部门：下面还有部门、还有人的删不掉（1005）")
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('system:user:edit')")
    @OperLog(module = "部门", action = "删除")
    public R<Void> delete(@PathVariable("id") Long id) {
        deptService.delete(id);
        return R.ok();
    }
}
