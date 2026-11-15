package com.lingmao.scm.module.basic.controller;

import com.lingmao.scm.common.log.OperLog;
import com.lingmao.scm.common.page.PageResult;
import com.lingmao.scm.common.result.R;
import com.lingmao.scm.module.basic.dto.ColorOption;
import com.lingmao.scm.module.basic.dto.ColorQuery;
import com.lingmao.scm.module.basic.dto.ColorSaveReq;
import com.lingmao.scm.module.basic.entity.Color;
import com.lingmao.scm.module.basic.service.ColorService;
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

@Tag(name = "颜色")
@RestController
@RequestMapping("/api/colors")
@RequiredArgsConstructor
public class ColorController {

    private final ColorService colorService;

    @Operation(summary = "分页")
    @GetMapping
    @PreAuthorize("hasAuthority('basic:basics:list')")
    public R<PageResult<Color>> page(@Valid ColorQuery query) {
        return R.ok(colorService.page(query));
    }

    @Operation(summary = "下拉：启用的（登录即可）")
    @GetMapping("/options")
    public R<List<ColorOption>> options() {
        return R.ok(colorService.options());
    }

    @Operation(summary = "详情")
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('basic:basics:list')")
    public R<Color> get(@PathVariable("id") Long id) {
        return R.ok(colorService.getById(id));
    }

    @Operation(summary = "新增，返回新 id")
    @PostMapping
    @PreAuthorize("hasAuthority('basic:basics:add')")
    @OperLog(module = "颜色", action = "新增")
    public R<Long> create(@Valid @RequestBody ColorSaveReq req) {
        return R.ok(colorService.create(req));
    }

    @Operation(summary = "修改（编码不能改）")
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('basic:basics:edit')")
    @OperLog(module = "颜色", action = "修改")
    public R<Void> update(@PathVariable("id") Long id, @Valid @RequestBody ColorSaveReq req) {
        colorService.update(id, req);
        return R.ok();
    }

    @Operation(summary = "删除")
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('basic:basics:remove')")
    @OperLog(module = "颜色", action = "删除")
    public R<Void> delete(@PathVariable("id") Long id) {
        colorService.delete(id);
        return R.ok();
    }

    @Operation(summary = "启用")
    @PostMapping("/{id}/enable")
    @PreAuthorize("hasAuthority('basic:basics:edit')")
    @OperLog(module = "颜色", action = "启用")
    public R<Void> enable(@PathVariable("id") Long id) {
        colorService.enable(id);
        return R.ok();
    }

    @Operation(summary = "停用")
    @PostMapping("/{id}/disable")
    @PreAuthorize("hasAuthority('basic:basics:edit')")
    @OperLog(module = "颜色", action = "停用")
    public R<Void> disable(@PathVariable("id") Long id) {
        colorService.disable(id);
        return R.ok();
    }
}
