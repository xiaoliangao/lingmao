package com.lingmao.scm.module.system.controller;

import com.lingmao.scm.common.page.PageResult;
import com.lingmao.scm.common.result.R;
import com.lingmao.scm.module.system.dto.IdsReq;
import com.lingmao.scm.module.system.dto.UserCreateReq;
import com.lingmao.scm.module.system.dto.UserPasswordReq;
import com.lingmao.scm.module.system.dto.UserQuery;
import com.lingmao.scm.module.system.dto.UserUpdateReq;
import com.lingmao.scm.module.system.dto.UserVO;
import com.lingmao.scm.module.system.service.UserService;
import com.lingmao.scm.security.LoginUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
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

@Tag(name = "用户管理")
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @Operation(summary = "分页查询")
    @GetMapping
    @PreAuthorize("hasAuthority('system:user:list')")
    public R<PageResult<UserVO>> page(@Valid UserQuery query) {
        return R.ok(userService.page(query));
    }

    @Operation(summary = "详情")
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('system:user:list')")
    public R<UserVO> get(@PathVariable("id") Long id) {
        return R.ok(userService.getById(id));
    }

    @Operation(summary = "新增，返回新 id")
    @PostMapping
    @PreAuthorize("hasAuthority('system:user:add')")
    public R<Long> create(@Valid @RequestBody UserCreateReq req) {
        return R.ok(userService.create(req));
    }

    @Operation(summary = "修改昵称、手机号、状态")
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('system:user:edit')")
    public R<Void> update(@PathVariable("id") Long id, @Valid @RequestBody UserUpdateReq req) {
        userService.update(id, req);
        return R.ok();
    }

    @Operation(summary = "删除（逻辑删除），不能删自己")
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('system:user:remove')")
    public R<Void> delete(@PathVariable("id") Long id, @AuthenticationPrincipal LoginUser loginUser) {
        userService.delete(id, loginUser.userId());
        return R.ok();
    }

    @Operation(summary = "重置密码")
    @PostMapping("/{id}/reset-password")
    @PreAuthorize("hasAuthority('system:user:resetPwd')")
    public R<Void> resetPassword(@PathVariable("id") Long id, @Valid @RequestBody UserPasswordReq req) {
        userService.resetPassword(id, req.getPassword());
        return R.ok();
    }

    @Operation(summary = "用户的角色 id")
    @GetMapping("/{id}/roles")
    @PreAuthorize("hasAuthority('system:user:list')")
    public R<List<Long>> roleIds(@PathVariable("id") Long id) {
        return R.ok(userService.getRoleIds(id));
    }

    @Operation(summary = "重新分配角色")
    @PutMapping("/{id}/roles")
    @PreAuthorize("hasAuthority('system:user:edit')")
    public R<Void> assignRoles(@PathVariable("id") Long id, @Valid @RequestBody IdsReq req) {
        userService.assignRoles(id, req.getIds());
        return R.ok();
    }
}
