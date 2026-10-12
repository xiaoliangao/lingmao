package com.lingmao.scm.module.system.controller;

import com.lingmao.scm.common.page.PageResult;
import com.lingmao.scm.common.result.R;
import com.lingmao.scm.module.system.dto.DictTypeQuery;
import com.lingmao.scm.module.system.dto.DictTypeSaveReq;
import com.lingmao.scm.module.system.entity.DictType;
import com.lingmao.scm.module.system.service.DictTypeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Controller = HTTP 适配层：收参数、校验参数、调 Service、包成 R 返回。每个方法三行以内。
 * 为什么这么薄：业务放这里就没法复用（定时任务、别的模块也要调），也没法单测。
 * 路径约定：资源用复数名词 /api/dict-types；GET 查、POST 增、PUT 改、DELETE 删；动作类才用 POST /{id}/xxx。
 */
@Tag(name = "字典类型")
@RestController
@RequestMapping("/api/dict-types")
@RequiredArgsConstructor
public class DictTypeController {

    private final DictTypeService dictTypeService;

    @Operation(summary = "分页查询")
    @GetMapping
    public R<PageResult<DictType>> page(@Valid DictTypeQuery query) {
        return R.ok(dictTypeService.page(query));
    }

    @Operation(summary = "详情")
    @GetMapping("/{id}")
    public R<DictType> get(@PathVariable("id") Long id) {
        return R.ok(dictTypeService.getById(id));
    }

    @Operation(summary = "新增，返回新 id")
    @PostMapping
    public R<Long> create(@Valid @RequestBody DictTypeSaveReq req) {
        return R.ok(dictTypeService.create(req));
    }

    @Operation(summary = "修改")
    @PutMapping("/{id}")
    public R<Void> update(@PathVariable("id") Long id, @Valid @RequestBody DictTypeSaveReq req) {
        dictTypeService.update(id, req);
        return R.ok();
    }

    @Operation(summary = "删除（逻辑删除）")
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable("id") Long id) {
        dictTypeService.delete(id);
        return R.ok();
    }
}
