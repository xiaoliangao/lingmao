package com.lingmao.scm.module.system.controller;

import com.lingmao.scm.common.page.PageResult;
import com.lingmao.scm.common.log.OperLog;
import com.lingmao.scm.common.result.R;
import com.lingmao.scm.module.system.dto.IdsReq;
import com.lingmao.scm.module.system.dto.RoleQuery;
import com.lingmao.scm.module.system.dto.RoleSaveReq;
import com.lingmao.scm.module.system.entity.SysRole;
import com.lingmao.scm.module.system.service.RoleService;
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

@Tag(name = "角色管理")
@RestController
@RequestMapping("/api/roles")
@RequiredArgsConstructor
public class RoleController {

    private final RoleService roleService;

    @Operation(summary = "分页查询")
    @GetMapping
    @PreAuthorize("hasAuthority('system:role:list')")
    public R<PageResult<SysRole>> page(@Valid RoleQuery query) {
        return R.ok(roleService.page(query));
    }

    @Operation(summary = "全部启用的角色（给用户分配角色时的下拉选项）")
    @GetMapping("/options")
    @PreAuthorize("hasAnyAuthority('system:role:list', 'system:user:edit')")
    public R<List<SysRole>> options() {
        return R.ok(roleService.listEnabled());
    }

    @Operation(summary = "详情")
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('system:role:list')")
    public R<SysRole> get(@PathVariable("id") Long id) {
        return R.ok(roleService.getById(id));
    }

    @Operation(summary = "新增，返回新 id")
    @PostMapping
    @PreAuthorize("hasAuthority('system:role:add')")
    @OperLog(module = "角色管理", action = "新增")
    public R<Long> create(@Valid @RequestBody RoleSaveReq req) {
        return R.ok(roleService.create(req));
    }

    @Operation(summary = "修改")
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('system:role:edit')")
    @OperLog(module = "角色管理", action = "修改")
    public R<Void> update(@PathVariable("id") Long id, @Valid @RequestBody RoleSaveReq req) {
        roleService.update(id, req);
        return R.ok();
    }

    @Operation(summary = "删除（已分配给用户的不能删）")
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('system:role:remove')")
    @OperLog(module = "角色管理", action = "删除")
    public R<Void> delete(@PathVariable("id") Long id) {
        roleService.delete(id);
        return R.ok();
    }

    @Operation(summary = "角色已分配的菜单 id")
    @GetMapping("/{id}/menus")
    @PreAuthorize("hasAuthority('system:role:list')")
    public R<List<Long>> menuIds(@PathVariable("id") Long id) {
        return R.ok(roleService.getMenuIds(id));
    }

    @Operation(summary = "重新分配菜单")
    @PutMapping("/{id}/menus")
    @PreAuthorize("hasAuthority('system:role:edit')")
    @OperLog(module = "角色管理", action = "分配菜单")
    public R<Void> assignMenus(@PathVariable("id") Long id, @Valid @RequestBody IdsReq req) {
        roleService.assignMenus(id, req.getIds());
        return R.ok();
    }
}
