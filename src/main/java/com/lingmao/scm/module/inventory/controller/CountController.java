package com.lingmao.scm.module.inventory.controller;

import com.lingmao.scm.common.idempotency.IdempotentCreate;
import com.lingmao.scm.common.idempotency.IdempotencyReplayGuard;
import tools.jackson.databind.JsonNode;

import com.lingmao.scm.common.excel.Excel;
import com.lingmao.scm.common.excel.ImportResult;
import com.lingmao.scm.common.log.OperLog;
import com.lingmao.scm.common.page.PageResult;
import com.lingmao.scm.common.result.R;
import com.lingmao.scm.module.biz.dto.AttachmentReq;
import com.lingmao.scm.module.biz.dto.AttachmentVO;
import com.lingmao.scm.module.biz.dto.DocActionReq;
import com.lingmao.scm.module.biz.dto.DocLogVO;
import com.lingmao.scm.module.biz.entity.BizType;
import com.lingmao.scm.module.biz.service.DocCommonService;
import com.lingmao.scm.module.inventory.dto.CountAddItemsReq;
import com.lingmao.scm.module.inventory.dto.CountCreateReq;
import com.lingmao.scm.module.inventory.dto.CountCreatedVO;
import com.lingmao.scm.module.inventory.dto.CountDetailVO;
import com.lingmao.scm.module.inventory.dto.CountOpenWarehouseVO;
import com.lingmao.scm.module.inventory.dto.CountQuery;
import com.lingmao.scm.module.inventory.dto.CountRowVO;
import com.lingmao.scm.module.inventory.dto.CountSaveReq;
import com.lingmao.scm.module.inventory.service.CountService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
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

/** 物料盘点单（docs/api/04 §3.5） */
@Tag(name = "物料盘点单")
@RestController
@RequestMapping("/api/inventory-counts")
@RequiredArgsConstructor
public class CountController extends IdempotencyReplayGuard {

    private final CountService countService;
    private final DocCommonService docCommonService;
    private final Clock clock;

    @Operation(summary = "列表，新的在前")
    @GetMapping
    @PreAuthorize("hasAuthority('inventory:count:list')")
    public R<PageResult<CountRowVO>> page(@Valid CountQuery query) {
        return R.ok(countService.page(query));
    }

    @Operation(summary = "详情")
    @GetMapping("/{id:\\d+}")
    @PreAuthorize("hasAuthority('inventory:count:list')")
    public R<CountDetailVO> detail(@PathVariable("id") long id) {
        return R.ok(countService.detail(id));
    }

    @Operation(summary = "有没审核的盘点单的仓库（新建时灰掉）")
    @GetMapping("/open-warehouses")
    @PreAuthorize("hasAuthority('inventory:count:add')")
    public R<List<CountOpenWarehouseVO>> openWarehouses() {
        return R.ok(countService.openWarehouses());
    }

    @Operation(summary = "新建：按范围带出明细、拍下账面数（这个仓已有没审核的 1000）")
    @PostMapping
    @PreAuthorize("hasAuthority('inventory:count:add')")
    @OperLog(module = "物料盘点单", action = "新建")
    @IdempotentCreate("COUNT_CREATE")
    public R<CountCreatedVO> create(@Valid @RequestBody CountCreateReq req) {
        return R.ok(countService.create(req));
    }

    @Operation(summary = "存草稿（实盘数、备注、删行），返回新的 version")
    @PutMapping("/{id:\\d+}")
    @PreAuthorize("hasAuthority('inventory:count:edit')")
    @OperLog(module = "物料盘点单", action = "保存")
    public R<Integer> update(@PathVariable("id") long id, @Valid @RequestBody CountSaveReq req) {
        return R.ok(countService.update(id, req));
    }

    @Operation(summary = "删草稿（过过账的只能作废）")
    @DeleteMapping("/{id:\\d+}")
    @PreAuthorize("hasAuthority('inventory:count:remove')")
    @OperLog(module = "物料盘点单", action = "删除")
    public R<Void> delete(@PathVariable("id") long id) {
        countService.delete(id);
        return R.ok();
    }

    @Operation(summary = "从物料库加物料，返回加完的详情")
    @PostMapping("/{id:\\d+}/items")
    @PreAuthorize("hasAuthority('inventory:count:edit')")
    @OperLog(module = "物料盘点单", action = "加物料")
    public R<CountDetailVO> addItems(@PathVariable("id") long id, @Valid @RequestBody CountAddItemsReq req) {
        return R.ok(countService.addItems(id, req));
    }

    @Operation(summary = "重新取账面数，返回新的详情")
    @PostMapping("/{id:\\d+}/refresh-book")
    @PreAuthorize("hasAuthority('inventory:count:edit')")
    @OperLog(module = "物料盘点单", action = "重新取账面数")
    public R<CountDetailVO> refreshBook(@PathVariable("id") long id, @Valid @RequestBody DocActionReq req) {
        return R.ok(countService.refreshBook(id, req));
    }

    @Operation(summary = "提交（实盘数都要填）")
    @PostMapping("/{id:\\d+}/submit")
    @PreAuthorize("hasAuthority('inventory:count:submit')")
    @OperLog(module = "物料盘点单", action = "提交")
    public R<Void> submit(@PathVariable("id") long id, @Valid @RequestBody DocActionReq req) {
        countService.act(id, "submit", req);
        return R.ok();
    }

    @Operation(summary = "撤回（制单人）")
    @PostMapping("/{id:\\d+}/withdraw")
    @PreAuthorize("hasAuthority('inventory:count:submit')")
    @OperLog(module = "物料盘点单", action = "撤回")
    public R<Void> withdraw(@PathVariable("id") long id, @Valid @RequestBody DocActionReq req) {
        countService.act(id, "withdraw", req);
        return R.ok();
    }

    @Operation(summary = "驳回（原因必填）")
    @PostMapping("/{id:\\d+}/reject")
    @PreAuthorize("hasAuthority('inventory:count:audit')")
    @OperLog(module = "物料盘点单", action = "驳回")
    public R<Void> reject(@PathVariable("id") long id, @Valid @RequestBody DocActionReq req) {
        countService.act(id, "reject", req);
        return R.ok();
    }

    @Operation(summary = "审核过账：按差异调库存（调完是负的 1001）")
    @PostMapping("/{id:\\d+}/audit")
    @PreAuthorize("hasAuthority('inventory:count:audit')")
    @OperLog(module = "物料盘点单", action = "审核过账")
    public R<Void> audit(@PathVariable("id") long id, @Valid @RequestBody DocActionReq req) {
        countService.act(id, "audit", req);
        return R.ok();
    }

    @Operation(summary = "反审核：调整退回去，回到草稿")
    @PostMapping("/{id:\\d+}/unaudit")
    @PreAuthorize("hasAuthority('inventory:count:audit')")
    @OperLog(module = "物料盘点单", action = "反审核")
    public R<Void> unaudit(@PathVariable("id") long id, @Valid @RequestBody DocActionReq req) {
        countService.act(id, "unaudit", req);
        return R.ok();
    }

    @Operation(summary = "作废：过过账又反审核回来的草稿（没过过账的直接删）")
    @PostMapping("/{id:\\d+}/void")
    @PreAuthorize("hasAuthority('inventory:count:remove')")
    @OperLog(module = "物料盘点单", action = "作废")
    public R<Void> voidCount(@PathVariable("id") long id, @Valid @RequestBody DocActionReq req) {
        countService.act(id, "void", req);
        return R.ok();
    }

    @Operation(summary = "导出盘点表（也是导回实盘数的模板）")
    @GetMapping("/{id:\\d+}/sheet")
    @PreAuthorize("hasAuthority('inventory:count:export')")
    public ResponseEntity<byte[]> sheet(@PathVariable("id") long id) {
        return Excel.download("盘点表-" + countService.no(id) + ".xlsx", countService.sheet(id));
    }

    @Operation(summary = "导回实盘数（草稿）")
    @PostMapping("/{id:\\d+}/import")
    @PreAuthorize("hasAuthority('inventory:count:import')")
    @OperLog(module = "物料盘点单", action = "导回实盘数")
    public R<ImportResult> importSheet(@PathVariable("id") long id, @RequestParam("file") MultipartFile file) {
        return R.ok(countService.importSheet(id, file));
    }

    @Operation(summary = "导出列表：一行一张单")
    @GetMapping("/export")
    @PreAuthorize("hasAuthority('inventory:count:export')")
    public ResponseEntity<byte[]> export(@Valid CountQuery query) {
        return Excel.download(Excel.exportFileName("物料盘点单", LocalDate.now(clock)), countService.export(query));
    }

    // ---------- 单据公共接口（README §4.3） ----------

    @Operation(summary = "操作记录，新的在前")
    @GetMapping("/{id:\\d+}/logs")
    @PreAuthorize("hasAuthority('inventory:count:list')")
    public R<List<DocLogVO>> logs(@PathVariable("id") long id) {
        return R.ok(docCommonService.logs(BizType.INV_COUNT, id, countService::checkVisible));
    }

    @Operation(summary = "附件")
    @GetMapping("/{id:\\d+}/attachments")
    @PreAuthorize("hasAuthority('inventory:count:list')")
    public R<List<AttachmentVO>> attachments(@PathVariable("id") long id) {
        return R.ok(docCommonService.attachments(BizType.INV_COUNT, id, countService::checkVisible));
    }

    @Operation(summary = "附件整组替换")
    @PutMapping("/{id:\\d+}/attachments")
    @PreAuthorize("hasAuthority('inventory:count:edit')")
    @OperLog(module = "物料盘点单", action = "换附件")
    public R<List<AttachmentVO>> replaceAttachments(@PathVariable("id") long id, @RequestBody List<@Valid AttachmentReq> files) {
        return R.ok(docCommonService.replaceAttachments(BizType.INV_COUNT, id, files, countService::checkEditable));
    }

    /** 重放只读检查当前对象范围，不能因为曾经成功就绕过之后的降权。 */
    @Override
    public void authorizeReplay(String operation, Object[] arguments, JsonNode result) {
        if (result.isArray()) {
            result.forEach(row -> countService.checkVisible(row.get("id").longValue()));
        } else {
            countService.checkVisible(result.get("id").longValue());
        }
    }
}
