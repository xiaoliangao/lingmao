package com.lingmao.scm.module.basic.controller;

import com.lingmao.scm.common.log.OperLog;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaType;
import com.lingmao.scm.common.excel.ImportResult;
import com.lingmao.scm.common.excel.Excel;
import com.lingmao.scm.common.result.R;
import com.lingmao.scm.module.basic.dto.CategorySaveReq;
import com.lingmao.scm.module.basic.dto.TreeNode;
import com.lingmao.scm.module.basic.entity.Category;
import com.lingmao.scm.module.basic.service.CategoryService;
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

@Tag(name = "品类")
@RestController
@RequestMapping("/api/categories")
@RequiredArgsConstructor
public class CategoryController {

    private final CategoryService categoryService;

    @Operation(summary = "整棵树（停用的也在）")
    @GetMapping
    @PreAuthorize("hasAuthority('basic:basics:list')")
    public R<List<TreeNode>> list() {
        return R.ok(categoryService.tree());
    }

    @Operation(summary = "下拉用的树：只有启用的（登录即可）")
    @GetMapping("/tree")
    public R<List<TreeNode>> tree() {
        return R.ok(categoryService.enabledTree());
    }

    @Operation(summary = "详情")
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('basic:basics:list')")
    public R<Category> get(@PathVariable("id") Long id) {
        return R.ok(categoryService.getById(id));
    }

    @Operation(summary = "新增，返回新 id")
    @PostMapping
    @PreAuthorize("hasAuthority('basic:basics:add')")
    @OperLog(module = "品类", action = "新增")
    public R<Long> create(@Valid @RequestBody CategorySaveReq req) {
        return R.ok(categoryService.create(req));
    }

    @Operation(summary = "修改（上级不能改）")
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('basic:basics:edit')")
    @OperLog(module = "品类", action = "修改")
    public R<Void> update(@PathVariable("id") Long id, @Valid @RequestBody CategorySaveReq req) {
        categoryService.update(id, req);
        return R.ok();
    }

    @Operation(summary = "删除（有下级的不能删）")
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('basic:basics:remove')")
    @OperLog(module = "品类", action = "删除")
    public R<Void> delete(@PathVariable("id") Long id) {
        categoryService.delete(id);
        return R.ok();
    }

    @Operation(summary = "启用")
    @PostMapping("/{id}/enable")
    @PreAuthorize("hasAuthority('basic:basics:edit')")
    @OperLog(module = "品类", action = "启用")
    public R<Void> enable(@PathVariable("id") Long id) {
        categoryService.enable(id);
        return R.ok();
    }

    @Operation(summary = "停用")
    @PostMapping("/{id}/disable")
    @PreAuthorize("hasAuthority('basic:basics:edit')")
    @OperLog(module = "品类", action = "停用")
    public R<Void> disable(@PathVariable("id") Long id) {
        categoryService.disable(id);
        return R.ok();
    }

    @Operation(summary = "导入模板：领猫「基础档案 › 产品类目」导出的列（分类名称、分类编码、状态、排序），RFC 018 §8.7")
    @GetMapping("/import-template")
    @PreAuthorize("hasAuthority('basic:basics:add')")
    public ResponseEntity<byte[]> importTemplate() {
        return Excel.download("品类导入模板.xlsx", categoryService.importTemplate());
    }

    /**
     * 领猫导出的表直接能导：分类名称是用 > 连起来的路径；扩展一到扩展七、描述认得、不导
     */
    @Operation(summary = "导入品类：按路径对，已有的改编码和状态，没有的建上；有一行不对整批不导（RFC 018 §8.7）")
    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('basic:basics:add') and hasAuthority('basic:basics:edit')")
    @OperLog(module = "品类", action = "导入")
    public R<ImportResult> importExcel(@RequestParam("file") MultipartFile file) {
        return R.ok(categoryService.importExcel(file));
    }
}
