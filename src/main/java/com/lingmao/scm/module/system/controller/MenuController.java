package com.lingmao.scm.module.system.controller;

import com.lingmao.scm.common.result.R;
import com.lingmao.scm.module.system.dto.MenuSaveReq;
import com.lingmao.scm.module.system.dto.MenuVO;
import com.lingmao.scm.module.system.entity.SysMenu;
import com.lingmao.scm.module.system.service.MenuService;
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

@Tag(name = "菜单管理")
@RestController
@RequestMapping("/api/menus")
@RequiredArgsConstructor
public class MenuController {

    private final MenuService menuService;

    @Operation(summary = "整棵菜单树（含按钮）")
    @GetMapping("/tree")
    @PreAuthorize("hasAnyAuthority('system:menu:list', 'system:role:edit')")
    public R<List<MenuVO>> tree() {
        return R.ok(menuService.tree());
    }

    @Operation(summary = "详情")
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('system:menu:list')")
    public R<SysMenu> get(@PathVariable("id") Long id) {
        return R.ok(menuService.getById(id));
    }

    @Operation(summary = "新增，返回新 id")
    @PostMapping
    @PreAuthorize("hasAuthority('system:menu:add')")
    public R<Long> create(@Valid @RequestBody MenuSaveReq req) {
        return R.ok(menuService.create(req));
    }

    @Operation(summary = "修改")
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('system:menu:edit')")
    public R<Void> update(@PathVariable("id") Long id, @Valid @RequestBody MenuSaveReq req) {
        menuService.update(id, req);
        return R.ok();
    }

    @Operation(summary = "删除（有下级的不能删）")
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('system:menu:remove')")
    public R<Void> delete(@PathVariable("id") Long id) {
        menuService.delete(id);
        return R.ok();
    }
}
