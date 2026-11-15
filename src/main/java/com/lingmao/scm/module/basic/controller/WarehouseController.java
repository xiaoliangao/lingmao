package com.lingmao.scm.module.basic.controller;

import com.lingmao.scm.common.log.OperLog;
import com.lingmao.scm.common.page.PageResult;
import com.lingmao.scm.common.result.R;
import com.lingmao.scm.module.basic.dto.WarehouseOption;
import com.lingmao.scm.module.basic.dto.WarehouseQuery;
import com.lingmao.scm.module.basic.dto.WarehouseSaveReq;
import com.lingmao.scm.module.basic.dto.WarehouseVO;
import com.lingmao.scm.module.basic.service.WarehouseService;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "仓库")
@RestController
@RequestMapping("/api/warehouses")
@RequiredArgsConstructor
public class WarehouseController {

    private final WarehouseService warehouseService;

    @Operation(summary = "分页，能按类型筛")
    @GetMapping
    @PreAuthorize("hasAuthority('basic:basics:list')")
    public R<PageResult<WarehouseVO>> page(@Valid WarehouseQuery query) {
        return R.ok(warehouseService.page(query));
    }

    @Operation(summary = "下拉：启用的，能按类型筛（登录即可）")
    @GetMapping("/options")
    public R<List<WarehouseOption>> options(@RequestParam(name = "type", required = false) String type) {
        return R.ok(warehouseService.options(type));
    }

    @Operation(summary = "详情")
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('basic:basics:list')")
    public R<WarehouseVO> get(@PathVariable("id") Long id) {
        return R.ok(warehouseService.getById(id));
    }

    @Operation(summary = "新增物料仓、成品仓，返回新 id（工厂仓不能手建）")
    @PostMapping
    @PreAuthorize("hasAuthority('basic:basics:add')")
    @OperLog(module = "仓库", action = "新增")
    public R<Long> create(@Valid @RequestBody WarehouseSaveReq req) {
        return R.ok(warehouseService.create(req));
    }

    @Operation(summary = "修改（编码、类型不能改；工厂仓的名称也不能改）")
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('basic:basics:edit')")
    @OperLog(module = "仓库", action = "修改")
    public R<Void> update(@PathVariable("id") Long id, @Valid @RequestBody WarehouseSaveReq req) {
        warehouseService.update(id, req);
        return R.ok();
    }

    @Operation(summary = "删除（工厂仓不能在这里删）")
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('basic:basics:remove')")
    @OperLog(module = "仓库", action = "删除")
    public R<Void> delete(@PathVariable("id") Long id) {
        warehouseService.delete(id);
        return R.ok();
    }

    @Operation(summary = "启用")
    @PostMapping("/{id}/enable")
    @PreAuthorize("hasAuthority('basic:basics:edit')")
    @OperLog(module = "仓库", action = "启用")
    public R<Void> enable(@PathVariable("id") Long id) {
        warehouseService.enable(id);
        return R.ok();
    }

    @Operation(summary = "停用")
    @PostMapping("/{id}/disable")
    @PreAuthorize("hasAuthority('basic:basics:edit')")
    @OperLog(module = "仓库", action = "停用")
    public R<Void> disable(@PathVariable("id") Long id) {
        warehouseService.disable(id);
        return R.ok();
    }
}
