package com.lingmao.scm.module.inventory.controller;

import com.lingmao.scm.common.idempotency.IdempotentCreate;
import com.lingmao.scm.common.idempotency.IdempotencyReplayGuard;
import tools.jackson.databind.JsonNode;

import com.lingmao.scm.common.excel.Excel;
import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.common.log.OperLog;
import com.lingmao.scm.common.page.PageResult;
import com.lingmao.scm.common.result.R;
import com.lingmao.scm.module.biz.dto.AttachmentReq;
import com.lingmao.scm.module.biz.dto.AttachmentVO;
import com.lingmao.scm.module.biz.dto.DocActionReq;
import com.lingmao.scm.module.biz.dto.DocLogVO;
import com.lingmao.scm.module.biz.dto.DocNoticeVO;
import com.lingmao.scm.module.biz.dto.DocRef;
import com.lingmao.scm.module.biz.dto.NoticeHandleReq;
import com.lingmao.scm.module.biz.entity.BizType;
import com.lingmao.scm.module.biz.service.DocCommonService;
import com.lingmao.scm.module.inventory.dto.InvDocCreateReq;
import com.lingmao.scm.module.inventory.dto.InvDocCreatedVO;
import com.lingmao.scm.module.inventory.dto.InvDocDetailVO;
import com.lingmao.scm.module.inventory.dto.InvDocDraftVO;
import com.lingmao.scm.module.inventory.dto.InvDocFromSourceReq;
import com.lingmao.scm.module.inventory.dto.InvDocQuery;
import com.lingmao.scm.module.inventory.dto.InvDocRowVO;
import com.lingmao.scm.module.inventory.dto.InvDocSaveReq;
import com.lingmao.scm.module.inventory.entity.InvDocKind;
import com.lingmao.scm.module.inventory.entity.InvDocType;
import com.lingmao.scm.module.inventory.service.InvDocService;
import com.lingmao.scm.security.LoginUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
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

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

/** 物料入库单、物料出库单（docs/api/04 §3.2、§3.3）：一组接口，列表按 kind 分 */
@Tag(name = "物料入库单、出库单")
@RestController
@RequestMapping("/api/inventory-docs")
@RequiredArgsConstructor
public class InvDocController extends IdempotencyReplayGuard {

    private final InvDocService invDocService;
    private final DocCommonService docCommonService;
    private final Clock clock;

    @Operation(summary = "列表（kind 必填），新的在前")
    @GetMapping
    @PreAuthorize("hasAuthority('inventory:doc:list')")
    public R<PageResult<InvDocRowVO>> page(@Valid InvDocQuery query) {
        return R.ok(invDocService.page(query));
    }

    @Operation(summary = "详情；草稿且有出仓时每行带出仓现在的库存")
    @GetMapping("/{id:\\d+}")
    @PreAuthorize("hasAuthority('inventory:doc:list')")
    public R<InvDocDetailVO> detail(@PathVariable("id") long id) {
        return R.ok(invDocService.detail(id));
    }

    @Operation(summary = "手工新建草稿（退料入库不挂源单、其他入库、调拨、其他出库）")
    @PostMapping
    @PreAuthorize("hasAuthority('inventory:doc:add')")
    @OperLog(module = "物料出入库单", action = "新建")
    @IdempotentCreate("INVENTORY_CREATE")
    public R<InvDocCreatedVO> create(@Valid @RequestBody InvDocCreateReq req) {
        return R.ok(invDocService.create(req));
    }

    @Operation(summary = "改草稿，整体覆盖；返回新的 version")
    @PutMapping("/{id:\\d+}")
    @PreAuthorize("hasAuthority('inventory:doc:edit')")
    @OperLog(module = "物料出入库单", action = "保存")
    public R<Integer> update(@PathVariable("id") long id, @Valid @RequestBody InvDocSaveReq req) {
        return R.ok(invDocService.update(id, req));
    }

    @Operation(summary = "删草稿（过过账的只能作废）")
    @DeleteMapping("/{id:\\d+}")
    @PreAuthorize("hasAuthority('inventory:doc:remove')")
    @OperLog(module = "物料出入库单", action = "删除")
    public R<Void> delete(@PathVariable("id") long id) {
        invDocService.delete(id);
        return R.ok();
    }

    @Operation(summary = "审核过账（库存不够 1001，超上限 1004）")
    @PostMapping("/{id:\\d+}/audit")
    @PreAuthorize("hasAuthority('inventory:doc:audit')")
    @OperLog(module = "物料出入库单", action = "审核过账")
    public R<Void> audit(@PathVariable("id") long id, @Valid @RequestBody DocActionReq req) {
        invDocService.act(id, "audit", req);
        return R.ok();
    }

    @Operation(summary = "反审核：库存改回去（改回去是负的 1001），回到草稿")
    @PostMapping("/{id:\\d+}/unaudit")
    @PreAuthorize("hasAuthority('inventory:doc:audit')")
    @OperLog(module = "物料出入库单", action = "反审核")
    public R<Void> unaudit(@PathVariable("id") long id, @Valid @RequestBody DocActionReq req) {
        invDocService.act(id, "unaudit", req);
        return R.ok();
    }

    @Operation(summary = "作废：过过账又反审核回来的草稿（没过过账的直接删）")
    @PostMapping("/{id:\\d+}/void")
    @PreAuthorize("hasAuthority('inventory:doc:remove')")
    @OperLog(module = "物料出入库单", action = "作废")
    public R<Void> voidDoc(@PathVariable("id") long id, @Valid @RequestBody DocActionReq req) {
        invDocService.act(id, "void", req);
        return R.ok();
    }

    @Operation(summary = "从采购单带出入库 / 退货的明细（不存库）")
    @GetMapping("/from-purchase/{sourceId:\\d+}")
    @PreAuthorize("hasAuthority('inventory:doc:add')")
    public R<InvDocDraftVO> prefillFromPurchase(@PathVariable("sourceId") long sourceId, @Parameter(description = "要建的单据类型，取值见枚举") @RequestParam("docType") InvDocType docType,
                                                @Parameter(description = "只带这几行采购明细，不传 = 还能入（能退）的全部行")
                                                @RequestParam(value = "itemIds", required = false) List<Long> itemIds) {
        return R.ok(invDocService.prefill(BizType.PURCHASE_ORDER, sourceId, docType, itemIds, null));
    }

    @Operation(summary = "由采购单生成采购入库单、采购退货单（post = true 时马上过账，要审核权限）")
    @PostMapping("/from-purchase/{sourceId:\\d+}")
    @PreAuthorize("hasAuthority('inventory:doc:add')")
    @OperLog(module = "物料出入库单", action = "由采购单生成")
    public R<InvDocCreatedVO> fromPurchase(@PathVariable("sourceId") long sourceId, @Valid @RequestBody InvDocFromSourceReq req) {
        checkPost(req);
        return R.ok(invDocService.fromSource(BizType.PURCHASE_ORDER, sourceId, req));
    }

    @Operation(summary = "从生产单带出领料 / 退料的明细（不存库）")
    @GetMapping("/from-production/{sourceId:\\d+}")
    @PreAuthorize("hasAuthority('inventory:doc:add')")
    public R<InvDocDraftVO> prefillFromProduction(@PathVariable("sourceId") long sourceId, @Parameter(description = "要建的单据类型，取值见枚举") @RequestParam("docType") InvDocType docType,
                                                  @Parameter(description = "领料的出仓，不传 = 第一个启用的物料仓")
                                                  @RequestParam(value = "fromWarehouseId", required = false) Long fromWarehouseId) {
        return R.ok(invDocService.prefill(BizType.PRODUCTION_ORDER, sourceId, docType, null, fromWarehouseId));
    }

    @Operation(summary = "由生产单生成领料出库单、退料入库单（post = true 时马上过账，要审核权限）")
    @PostMapping("/from-production/{sourceId:\\d+}")
    @PreAuthorize("hasAuthority('inventory:doc:add')")
    @OperLog(module = "物料出入库单", action = "由生产单生成")
    public R<InvDocCreatedVO> fromProduction(@PathVariable("sourceId") long sourceId, @Valid @RequestBody InvDocFromSourceReq req) {
        checkPost(req);
        return R.ok(invDocService.fromSource(BizType.PRODUCTION_ORDER, sourceId, req));
    }

    /** 建好马上过账要 :add 和 :audit 两个权限（docs/api/04 §3.3） */
    private static void checkPost(InvDocFromSourceReq req) {
        if (req.isPost() && !LoginUser.hasAuthority("inventory:doc:audit")) {
            throw new BizException(403, "建好马上入库要有审核权限：先存草稿，让有权限的人审核");
        }
    }

    @Operation(summary = "导出：一行一条明细")
    @GetMapping("/export")
    @PreAuthorize("hasAuthority('inventory:doc:export')")
    public ResponseEntity<byte[]> export(@Valid InvDocQuery query) {
        String name = query.getKind() == InvDocKind.IN ? "物料入库单" : "物料出库单";
        return Excel.download(Excel.exportFileName(name, LocalDate.now(clock)), invDocService.export(query));
    }

    // ---------- 单据公共接口（README §4.3） ----------

    @Operation(summary = "操作记录，新的在前")
    @GetMapping("/{id:\\d+}/logs")
    @PreAuthorize("hasAuthority('inventory:doc:list')")
    public R<List<DocLogVO>> logs(@PathVariable("id") long id) {
        return R.ok(docCommonService.logs(BizType.INV_DOC, id, invDocService::checkVisible));
    }

    @Operation(summary = "附件")
    @GetMapping("/{id:\\d+}/attachments")
    @PreAuthorize("hasAuthority('inventory:doc:list')")
    public R<List<AttachmentVO>> attachments(@PathVariable("id") long id) {
        return R.ok(docCommonService.attachments(BizType.INV_DOC, id, invDocService::checkVisible));
    }

    @Operation(summary = "附件整组替换")
    @PutMapping("/{id:\\d+}/attachments")
    @PreAuthorize("hasAuthority('inventory:doc:edit')")
    @OperLog(module = "物料出入库单", action = "换附件")
    public R<List<AttachmentVO>> replaceAttachments(@PathVariable("id") long id, @RequestBody List<@Valid AttachmentReq> files) {
        return R.ok(docCommonService.replaceAttachments(BizType.INV_DOC, id, files, invDocService::checkEditable));
    }

    @Operation(summary = "下游单据（入库单、出库单没有下游，是空的）")
    @GetMapping("/{id:\\d+}/references")
    @PreAuthorize("hasAuthority('inventory:doc:list')")
    public R<List<DocRef>> references(@PathVariable("id") long id) {
        return R.ok(docCommonService.references(BizType.INV_DOC, id, invDocService::checkVisible));
    }

    @Operation(summary = "上游变更提醒")
    @GetMapping("/{id:\\d+}/notices")
    @PreAuthorize("hasAuthority('inventory:doc:list')")
    public R<List<DocNoticeVO>> notices(@PathVariable("id") long id) {
        return R.ok(docCommonService.notices(BizType.INV_DOC, id, invDocService::checkVisible));
    }

    @Operation(summary = "处理一条上游变更提醒")
    @PostMapping("/{id:\\d+}/notices/{noticeId:\\d+}/handle")
    @PreAuthorize("hasAuthority('inventory:doc:edit')")
    public R<Void> handleNotice(@PathVariable("id") long id, @PathVariable("noticeId") long noticeId,
                                @Valid @RequestBody NoticeHandleReq req) {
        docCommonService.handleNotice(BizType.INV_DOC, id, noticeId, req.getRemark(), invDocService::checkEditable);
        return R.ok();
    }

    /** 重放只读检查当前对象范围，不能因为曾经成功就绕过之后的降权。 */
    @Override
    public void authorizeReplay(String operation, Object[] arguments, JsonNode result) {
        if (result.isArray()) {
            result.forEach(row -> invDocService.checkVisible(row.get("id").longValue()));
        } else {
            invDocService.checkVisible(result.get("id").longValue());
        }
    }
}
