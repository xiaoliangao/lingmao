package com.lingmao.scm.module.system.controller;

import com.lingmao.scm.common.page.PageResult;
import com.lingmao.scm.common.result.R;
import com.lingmao.scm.module.system.dto.DictDataQuery;
import com.lingmao.scm.module.system.dto.DictDataSaveReq;
import com.lingmao.scm.module.system.entity.DictData;
import com.lingmao.scm.module.system.service.DictDataService;
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

import java.util.List;

@Tag(name = "字典数据")
@RestController
@RequestMapping("/api/dict-data")
@RequiredArgsConstructor
public class DictDataController {

    private final DictDataService dictDataService;

    @Operation(summary = "分页查询")
    @GetMapping
    public R<PageResult<DictData>> page(@Valid DictDataQuery query) {
        return R.ok(dictDataService.page(query));
    }

    @Operation(summary = "按类型取启用的选项（下拉框用，不分页）")
    @GetMapping("/by-type/{typeCode}")
    public R<List<DictData>> listByType(@PathVariable("typeCode") String typeCode) {
        return R.ok(dictDataService.listByType(typeCode));
    }

    @Operation(summary = "详情")
    @GetMapping("/{id}")
    public R<DictData> get(@PathVariable("id") Long id) {
        return R.ok(dictDataService.getById(id));
    }

    @Operation(summary = "新增，返回新 id")
    @PostMapping
    public R<Long> create(@Valid @RequestBody DictDataSaveReq req) {
        return R.ok(dictDataService.create(req));
    }

    @Operation(summary = "修改")
    @PutMapping("/{id}")
    public R<Void> update(@PathVariable("id") Long id, @Valid @RequestBody DictDataSaveReq req) {
        dictDataService.update(id, req);
        return R.ok();
    }

    @Operation(summary = "删除（逻辑删除）")
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable("id") Long id) {
        dictDataService.delete(id);
        return R.ok();
    }
}