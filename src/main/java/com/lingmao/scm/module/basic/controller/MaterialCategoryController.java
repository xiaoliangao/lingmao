package com.lingmao.scm.module.basic.controller;

import com.lingmao.scm.common.log.OperLog;
import com.lingmao.scm.common.result.R;
import com.lingmao.scm.module.basic.dto.MaterialCategorySaveReq;
import com.lingmao.scm.module.basic.dto.TreeNode;
import com.lingmao.scm.module.basic.entity.MaterialCategory;
import com.lingmao.scm.module.basic.service.MaterialCategoryService;
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

@Tag(name = "物料分类")
@RestController
@RequestMapping("/api/material-categories")
@RequiredArgsConstructor
public class MaterialCategoryController {

    private final MaterialCategoryService materialCategoryService;

    /** 物料页左边的树也用它（要列出停用的分类），所以有物料列表权限也能看 */
    @Operation(summary = "整棵树（停用的也在）")
    @GetMapping
    @PreAuthorize("hasAnyAuthority('basic:basics:list', 'basic:material:list')")
    public R<List<TreeNode>> list() {
        return R.ok(materialCategoryService.tree());
    }

    @Operation(summary = "下拉用的树：只有启用的（登录即可）")
    @GetMapping("/tree")
    public R<List<TreeNode>> tree() {
        return R.ok(materialCategoryService.enabledTree());
    }

    @Operation(summary = "详情")
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('basic:basics:list')")
    public R<MaterialCategory> get(@PathVariable("id") Long id) {
        return R.ok(materialCategoryService.getById(id));
    }

    @Operation(summary = "新增，返回新 id")
    @PostMapping
    @PreAuthorize("hasAuthority('basic:basics:add')")
    @OperLog(module = "物料分类", action = "新增")
    public R<Long> create(@Valid @RequestBody MaterialCategorySaveReq req) {
        return R.ok(materialCategoryService.create(req));
    }

    @Operation(summary = "修改（上级、编码不能改）")
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('basic:basics:edit')")
    @OperLog(module = "物料分类", action = "修改")
    public R<Void> update(@PathVariable("id") Long id, @Valid @RequestBody MaterialCategorySaveReq req) {
        materialCategoryService.update(id, req);
        return R.ok();
    }

    @Operation(summary = "删除（有下级的不能删）")
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('basic:basics:remove')")
    @OperLog(module = "物料分类", action = "删除")
    public R<Void> delete(@PathVariable("id") Long id) {
        materialCategoryService.delete(id);
        return R.ok();
    }

    @Operation(summary = "启用")
    @PostMapping("/{id}/enable")
    @PreAuthorize("hasAuthority('basic:basics:edit')")
    @OperLog(module = "物料分类", action = "启用")
    public R<Void> enable(@PathVariable("id") Long id) {
        materialCategoryService.enable(id);
        return R.ok();
    }

    @Operation(summary = "停用")
    @PostMapping("/{id}/disable")
    @PreAuthorize("hasAuthority('basic:basics:edit')")
    @OperLog(module = "物料分类", action = "停用")
    public R<Void> disable(@PathVariable("id") Long id) {
        materialCategoryService.disable(id);
        return R.ok();
    }
}
