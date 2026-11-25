package com.lingmao.scm.module.system.controller;

import com.lingmao.scm.common.page.PageResult;
import com.lingmao.scm.common.log.OperLog;
import com.lingmao.scm.common.result.R;
import com.lingmao.scm.module.system.dto.IdsReq;
import com.lingmao.scm.module.system.dto.UserCreateReq;
import com.lingmao.scm.module.system.dto.UserOption;
import com.lingmao.scm.module.system.dto.UserPasswordReq;
import com.lingmao.scm.module.system.dto.UserQuery;
import com.lingmao.scm.module.system.dto.UserUpdateReq;
import com.lingmao.scm.module.system.dto.UserVO;
import com.lingmao.scm.module.system.service.UserImportService;
import com.lingmao.scm.module.system.service.UserService;
import com.lingmao.scm.common.excel.Excel;
import com.lingmao.scm.common.excel.ImportResult;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MultipartFile;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "用户管理")
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;
    private final UserImportService userImportService;

    @Operation(summary = "分页查询：关键字查登录名、昵称、手机号；按部门（含下级）、角色、状态筛；每人带部门、角色")
    @GetMapping
    @PreAuthorize("hasAuthority('system:user:list')")
    public R<PageResult<UserVO>> page(@Valid UserQuery query) {
        return R.ok(userService.page(query));
    }

    /**
     * 不加 @PreAuthorize：业务页面选负责人、跟单员时都要用，登录就能调。
     * 所以只给 id、用户名、昵称，只给启用的，一次最多 20 个，用户管理的列表接口（要管理员权限）不对外放开
     */
    @Operation(summary = "选人下拉框：启用的账号，按用户名或昵称搜，最多 20 个（登录即可）")
    @GetMapping("/options")
    public R<List<UserOption>> options(@RequestParam(name = "keyword", required = false) String keyword) {
        return R.ok(userService.options(keyword));
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
    @OperLog(module = "用户管理", action = "新增")
    public R<Long> create(@Valid @RequestBody UserCreateReq req) {
        return R.ok(userService.create(req));
    }

    @Operation(summary = "修改昵称、手机号、状态，不能停用自己")
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('system:user:edit')")
    @OperLog(module = "用户管理", action = "修改")
    public R<Void> update(@PathVariable("id") Long id, @Valid @RequestBody UserUpdateReq req,
                          @AuthenticationPrincipal LoginUser loginUser) {
        userService.update(id, req, loginUser.userId());
        return R.ok();
    }

    @Operation(summary = "删除（逻辑删除），不能删自己")
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('system:user:remove')")
    @OperLog(module = "用户管理", action = "删除")
    public R<Void> delete(@PathVariable("id") Long id, @AuthenticationPrincipal LoginUser loginUser) {
        userService.delete(id, loginUser.userId());
        return R.ok();
    }

    @Operation(summary = "重置密码")
    @PostMapping("/{id}/reset-password")
    @PreAuthorize("hasAuthority('system:user:resetPwd')")
    @OperLog(module = "用户管理", action = "重置密码")
    public R<Void> resetPassword(@PathVariable("id") Long id, @Valid @RequestBody UserPasswordReq req) {
        userService.resetPassword(id, req.getPassword());
        return R.ok();
    }

    @Operation(summary = "账号导入模板：领猫用户导出的列名，加角色、品类线、设计师编号、默认主体（RFC 018 §3）")
    @GetMapping("/import-template")
    @PreAuthorize("hasAuthority('system:user:import')")
    public ResponseEntity<byte[]> importTemplate() {
        return Excel.download("账号导入模板.xlsx", userImportService.template());
    }

    /**
     * 初始密码放在表单里，不放地址里：地址会进 Nginx 的访问日志。操作日志只记参数名，不记值
     */
    @Operation(summary = "导入账号：一批人一个初始密码，第一次登录必须改；登录名已有的跳过（RFC 018 §3）")
    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('system:user:import')")
    @OperLog(module = "用户管理", action = "导入")
    public R<ImportResult> importExcel(@RequestParam("file") MultipartFile file,
                                       @io.swagger.v3.oas.annotations.Parameter(description = "这一批人共用的初始密码，8-32 位")
                                       @RequestParam("password") String password) {
        return R.ok(userImportService.importExcel(file, password));
    }

    @Operation(summary = "用户的角色 id")
    @GetMapping("/{id}/roles")
    @PreAuthorize("hasAuthority('system:user:list')")
    public R<List<Long>> roleIds(@PathVariable("id") Long id) {
        return R.ok(userService.getRoleIds(id));
    }

    @Operation(summary = "重新分配角色，不能去掉自己的超级管理员角色")
    @PutMapping("/{id}/roles")
    @PreAuthorize("hasAuthority('system:user:edit')")
    @OperLog(module = "用户管理", action = "分配角色")
    public R<Void> assignRoles(@PathVariable("id") Long id, @Valid @RequestBody IdsReq req,
                               @AuthenticationPrincipal LoginUser loginUser) {
        userService.assignRoles(id, req.getIds(), loginUser.userId());
        return R.ok();
    }
}
