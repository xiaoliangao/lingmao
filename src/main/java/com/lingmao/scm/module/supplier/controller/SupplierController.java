package com.lingmao.scm.module.supplier.controller;

import com.lingmao.scm.common.idempotency.IdempotentCreate;
import com.lingmao.scm.common.idempotency.IdempotencyReplayGuard;
import tools.jackson.databind.JsonNode;

import com.lingmao.scm.common.excel.Excel;
import com.lingmao.scm.common.excel.ImportResult;
import com.lingmao.scm.common.log.OperLog;
import com.lingmao.scm.common.page.PageResult;
import com.lingmao.scm.common.result.R;
import com.lingmao.scm.module.biz.dto.DocLogVO;
import com.lingmao.scm.module.supplier.dto.BankVO;
import com.lingmao.scm.module.supplier.dto.CertSaveReq;
import com.lingmao.scm.module.supplier.dto.CertVO;
import com.lingmao.scm.module.supplier.dto.ChangeVO;
import com.lingmao.scm.module.supplier.dto.ReasonReq;
import com.lingmao.scm.module.supplier.dto.SupplierOption;
import com.lingmao.scm.module.supplier.dto.SupplierQuery;
import com.lingmao.scm.module.supplier.dto.SupplierRowVO;
import com.lingmao.scm.module.supplier.dto.SupplierSaveReq;
import com.lingmao.scm.module.supplier.dto.SupplierSaveResult;
import com.lingmao.scm.module.supplier.dto.SupplierVO;
import com.lingmao.scm.module.supplier.entity.SupplierStatus;
import com.lingmao.scm.module.supplier.service.SupplierCertService;
import com.lingmao.scm.module.supplier.service.SupplierChangeService;
import com.lingmao.scm.module.supplier.service.SupplierExcelService;
import com.lingmao.scm.module.supplier.service.SupplierService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
import org.springframework.web.multipart.MultipartFile;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

/**
 * 供应商（RFC 004 §8）。权限码前缀 supplier:supplier:，各接口要哪个见注解。
 * 数字 id 的路径写成 {id:\d+}：/options、/export 这些不会被当成 id
 */
@Tag(name = "供应商")
@RestController
@RequestMapping("/api/suppliers")
@RequiredArgsConstructor
public class SupplierController extends IdempotencyReplayGuard {

    private final SupplierService supplierService;
    private final SupplierChangeService changeService;
    private final SupplierCertService certService;
    private final SupplierExcelService excelService;
    private final Clock clock;

    @Operation(summary = "分页查询（不出账号）")
    @GetMapping
    @PreAuthorize("hasAuthority('supplier:supplier:list')")
    public R<PageResult<SupplierRowVO>> page(@Valid SupplierQuery query) {
        return R.ok(supplierService.page(query));
    }

    @Operation(summary = "详情（账号遮成前 4 后 4）")
    @GetMapping("/{id:\\d+}")
    @PreAuthorize("hasAuthority('supplier:supplier:list')")
    public R<SupplierVO> detail(@PathVariable("id") long id) {
        return R.ok(supplierService.detail(id));
    }

    @Operation(summary = "银行信息全号（每调一次记一条操作日志）")
    @GetMapping("/{id:\\d+}/bank")
    @PreAuthorize("hasAuthority('supplier:supplier:bank')")
    @OperLog(module = "供应商", action = "查看银行账号")
    public R<BankVO> bank(@PathVariable("id") long id) {
        return R.ok(supplierService.bank(id));
    }

    @Operation(summary = "新建（待准入）")
    @PostMapping
    @PreAuthorize("hasAuthority('supplier:supplier:add')")
    @OperLog(module = "供应商", action = "新建")
    @IdempotentCreate("SUPPLIER_CREATE")
    public R<SupplierSaveResult> create(@Valid @RequestBody SupplierSaveReq req) {
        return R.ok(supplierService.create(req));
    }

    @Operation(summary = "保存：普通字段直接改，关键字段待准入时直接改、准入过的挂起等审批")
    @PutMapping("/{id:\\d+}")
    @PreAuthorize("hasAuthority('supplier:supplier:edit')")
    @OperLog(module = "供应商", action = "修改")
    public R<SupplierSaveResult> update(@PathVariable("id") long id, @Valid @RequestBody SupplierSaveReq req) {
        return R.ok(supplierService.update(id, req));
    }

    @Operation(summary = "删除（审批中、黑名单的不能删；被引用的返回 1005）")
    @DeleteMapping("/{id:\\d+}")
    @PreAuthorize("hasAuthority('supplier:supplier:remove')")
    @OperLog(module = "供应商", action = "删除")
    public R<Void> delete(@PathVariable("id") long id) {
        supplierService.delete(id);
        return R.ok();
    }

    // ------------------------------------------------------------------ 状态动作

    @Operation(summary = "申请准入（缺资料返回 1000，data 是缺项清单）")
    @PostMapping("/{id:\\d+}/submit")
    @PreAuthorize("hasAuthority('supplier:supplier:edit')")
    @OperLog(module = "供应商", action = "申请准入")
    public R<Void> submit(@PathVariable("id") long id) {
        supplierService.submit(id);
        return R.ok();
    }

    @Operation(summary = "撤回准入申请（只有申请人）")
    @PostMapping("/{id:\\d+}/withdraw")
    @PreAuthorize("hasAuthority('supplier:supplier:edit')")
    @OperLog(module = "供应商", action = "撤回准入申请")
    public R<Void> withdraw(@PathVariable("id") long id) {
        supplierService.withdraw(id);
        return R.ok();
    }

    @Operation(summary = "准入通过（不能是申请人）")
    @PostMapping("/{id:\\d+}/audit")
    @PreAuthorize("hasAuthority('supplier:supplier:admit')")
    @OperLog(module = "供应商", action = "准入通过")
    public R<Void> audit(@PathVariable("id") long id) {
        supplierService.audit(id);
        return R.ok();
    }

    @Operation(summary = "驳回准入申请")
    @PostMapping("/{id:\\d+}/reject")
    @PreAuthorize("hasAuthority('supplier:supplier:admit')")
    @OperLog(module = "供应商", action = "驳回准入申请")
    public R<Void> reject(@PathVariable("id") long id, @Valid @RequestBody ReasonReq req) {
        supplierService.reject(id, req.getReason().strip());
        return R.ok();
    }

    @Operation(summary = "停用")
    @PostMapping("/{id:\\d+}/disable")
    @PreAuthorize("hasAuthority('supplier:supplier:edit')")
    @OperLog(module = "供应商", action = "停用")
    public R<Void> disable(@PathVariable("id") long id) {
        supplierService.disable(id);
        return R.ok();
    }

    @Operation(summary = "启用")
    @PostMapping("/{id:\\d+}/enable")
    @PreAuthorize("hasAuthority('supplier:supplier:edit')")
    @OperLog(module = "供应商", action = "启用")
    public R<Void> enable(@PathVariable("id") long id) {
        supplierService.enable(id);
        return R.ok();
    }

    @Operation(summary = "拉黑")
    @PostMapping("/{id:\\d+}/blacklist")
    @PreAuthorize("hasAuthority('supplier:supplier:admit')")
    @OperLog(module = "供应商", action = "拉黑")
    public R<Void> blacklist(@PathVariable("id") long id, @Valid @RequestBody ReasonReq req) {
        supplierService.blacklist(id, req.getReason().strip());
        return R.ok();
    }

    @Operation(summary = "解除黑名单（准入过的回停用，没准入过的回待准入）")
    @PostMapping("/{id:\\d+}/unblacklist")
    @PreAuthorize("hasAuthority('supplier:supplier:admit')")
    @OperLog(module = "供应商", action = "解除黑名单")
    public R<Void> unblacklist(@PathVariable("id") long id, @Valid @RequestBody ReasonReq req) {
        supplierService.unblacklist(id, req.getReason().strip());
        return R.ok();
    }

    @Operation(summary = "操作记录")
    @GetMapping("/{id:\\d+}/logs")
    @PreAuthorize("hasAuthority('supplier:supplier:list')")
    public R<List<DocLogVO>> logs(@PathVariable("id") long id) {
        return R.ok(supplierService.logs(id));
    }

    // ------------------------------------------------------------------ 关键字段改动

    @Operation(summary = "改动记录（新的在前）")
    @GetMapping("/{id:\\d+}/changes")
    @PreAuthorize("hasAuthority('supplier:supplier:list')")
    public R<List<ChangeVO>> changes(@PathVariable("id") long id) {
        return R.ok(changeService.list(id));
    }

    @Operation(summary = "批准改动（不能是申请人）")
    @PostMapping("/{id:\\d+}/changes/{changeId:\\d+}/approve")
    @PreAuthorize("hasAuthority('supplier:supplier:admit')")
    @OperLog(module = "供应商", action = "批准改动")
    public R<Void> approveChange(@PathVariable("id") long id, @PathVariable("changeId") long changeId) {
        changeService.approve(id, changeId);
        return R.ok();
    }

    @Operation(summary = "驳回改动")
    @PostMapping("/{id:\\d+}/changes/{changeId:\\d+}/reject")
    @PreAuthorize("hasAuthority('supplier:supplier:admit')")
    @OperLog(module = "供应商", action = "驳回改动")
    public R<Void> rejectChange(@PathVariable("id") long id, @PathVariable("changeId") long changeId,
                                @Valid @RequestBody ReasonReq req) {
        changeService.reject(id, changeId, req.getReason().strip());
        return R.ok();
    }

    @Operation(summary = "撤回改动（只有申请人）")
    @PostMapping("/{id:\\d+}/changes/{changeId:\\d+}/withdraw")
    @PreAuthorize("hasAuthority('supplier:supplier:edit')")
    @OperLog(module = "供应商", action = "撤回改动")
    public R<Void> withdrawChange(@PathVariable("id") long id, @PathVariable("changeId") long changeId) {
        changeService.withdraw(id, changeId);
        return R.ok();
    }

    // ------------------------------------------------------------------ 资质

    @Operation(summary = "资质证照")
    @GetMapping("/{id:\\d+}/certs")
    @PreAuthorize("hasAuthority('supplier:supplier:list')")
    public R<List<CertVO>> certs(@PathVariable("id") long id) {
        return R.ok(certService.list(id));
    }

    @Operation(summary = "加资质（文件先走上传接口）")
    @PostMapping("/{id:\\d+}/certs")
    @PreAuthorize("hasAuthority('supplier:supplier:edit')")
    @OperLog(module = "供应商", action = "加资质")
    @IdempotentCreate("SUPPLIER_CERT_CREATE")
    public R<Long> addCert(@PathVariable("id") long id, @Valid @RequestBody CertSaveReq req) {
        return R.ok(certService.add(id, req));
    }

    @Operation(summary = "改资质")
    @PutMapping("/{id:\\d+}/certs/{certId:\\d+}")
    @PreAuthorize("hasAuthority('supplier:supplier:edit')")
    @OperLog(module = "供应商", action = "改资质")
    public R<Void> updateCert(@PathVariable("id") long id, @PathVariable("certId") long certId,
                              @Valid @RequestBody CertSaveReq req) {
        certService.update(id, certId, req);
        return R.ok();
    }

    @Operation(summary = "删资质（文件不删）")
    @DeleteMapping("/{id:\\d+}/certs/{certId:\\d+}")
    @PreAuthorize("hasAuthority('supplier:supplier:edit')")
    @OperLog(module = "供应商", action = "删资质")
    public R<Void> deleteCert(@PathVariable("id") long id, @PathVariable("certId") long certId) {
        certService.delete(id, certId);
        return R.ok();
    }

    // ------------------------------------------------------------------ 下拉、导入导出

    @Operation(summary = "下拉：按分类（逗号隔开多个，任一个就算）、按能不能用筛（usage 不传 = SAMPLE）、按品类线筛（productLine）")
    @GetMapping("/options")
    public R<List<SupplierOption>> options(@Parameter(description = "供应商分类（字典值），可以传几个；不传是全部") @RequestParam(value = "category", required = false) List<String> category,
                                           @Parameter(description = "选来干什么：SAMPLE 打样、录价格（待准入、准入审批中、合作中都出）/ BULK 采购单、生产单（只出合作中的）") @RequestParam(value = "usage", required = false) SupplierService.Usage usage,
                                           @RequestParam(value = "keyword", required = false) String keyword,
                                           @RequestParam(value = "includeId", required = false) Long includeId,
                                           @RequestParam(value = "productLine", required = false) String productLine) {
        return R.ok(supplierService.options(category, usage, keyword, includeId, productLine));
    }

    @Operation(summary = "导入模板（领猫导出的样子）")
    @GetMapping("/import-template")
    @PreAuthorize("hasAuthority('supplier:supplier:import')")
    public ResponseEntity<byte[]> importTemplate() {
        return Excel.download("供应商导入模板.xlsx", excelService.importTemplate());
    }

    @Operation(summary = "导入：status 是 ACTIVE（默认，要 admit）或 PENDING_ADMIT")
    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('supplier:supplier:import')")
    @OperLog(module = "供应商", action = "导入")
    public R<ImportResult> importExcel(@RequestParam("file") MultipartFile file,
                                       @Parameter(description = "导进来的供应商是什么状态：ACTIVE 合作中（默认，要有准入权限）/ PENDING_ADMIT 待准入") @RequestParam(value = "status", required = false) SupplierStatus status) {
        return R.ok(excelService.importExcel(file, status));
    }

    @Operation(summary = "导出（不出账号、户名、开户行）")
    @GetMapping("/export")
    @PreAuthorize("hasAuthority('supplier:supplier:export')")
    public ResponseEntity<byte[]> export(@Valid SupplierQuery query) {
        return Excel.download(Excel.exportFileName("供应商", LocalDate.now(clock)), excelService.export(query));
    }
    @Override
    public void authorizeReplay(String operation, Object[] arguments, JsonNode result) {
        if ("SUPPLIER_CREATE".equals(operation)) supplierService.detail(result.get("id").longValue());
        else supplierService.detail(((Number) arguments[0]).longValue());
    }
}
