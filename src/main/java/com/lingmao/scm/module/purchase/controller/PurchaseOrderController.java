package com.lingmao.scm.module.purchase.controller;

import com.lingmao.scm.common.excel.Excel;
import com.lingmao.scm.common.log.OperLog;
import com.lingmao.scm.common.page.PageResult;
import com.lingmao.scm.common.result.R;
import com.lingmao.scm.module.biz.dto.AttachmentReq;
import com.lingmao.scm.module.biz.dto.AttachmentVO;
import com.lingmao.scm.module.biz.dto.DocActionReq;
import com.lingmao.scm.module.biz.dto.DocLogVO;
import com.lingmao.scm.module.biz.dto.DocNoticeVO;
import com.lingmao.scm.module.biz.dto.DocRef;
import com.lingmao.scm.module.biz.dto.DocSaved;
import com.lingmao.scm.module.biz.dto.NoticeHandleReq;
import com.lingmao.scm.module.biz.entity.BizType;
import com.lingmao.scm.module.biz.service.DocCommonService;
import com.lingmao.scm.module.purchase.dto.FollowReq;
import com.lingmao.scm.module.purchase.dto.FromMrpReq;
import com.lingmao.scm.module.purchase.dto.FromReorderReq;
import com.lingmao.scm.module.purchase.dto.PendingArrivalCounts;
import com.lingmao.scm.module.purchase.dto.PendingArrivalQuery;
import com.lingmao.scm.module.purchase.dto.PendingArrivalRowVO;
import com.lingmao.scm.module.purchase.dto.PurchaseCreateReq;
import com.lingmao.scm.module.purchase.dto.PurchaseDefaultsQuery;
import com.lingmao.scm.module.purchase.dto.PurchaseDefaultsVO;
import com.lingmao.scm.module.purchase.dto.PurchaseDetailVO;
import com.lingmao.scm.module.purchase.dto.PurchaseQuery;
import com.lingmao.scm.module.purchase.dto.PurchaseRowVO;
import com.lingmao.scm.module.purchase.dto.PurchaseSaveReq;
import com.lingmao.scm.module.purchase.service.PurchaseOrderService;
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
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

/**
 * 采购单（docs/api/03 §3.3）、待到料（§3.4）。权限码 purchase:order:*；审核、驳回只要登录，谁能审由多级审批按当前这一级判断
 * （Service 里查），撤回只给制单人。到货入库、退货在库存那边开单（inventory:doc:add）
 */
@Tag(name = "采购单")
@RestController
@RequestMapping("/api/purchase-orders")
@RequiredArgsConstructor
public class PurchaseOrderController {

    private final PurchaseOrderService purchaseOrderService;
    private final DocCommonService docCommonService;
    private final Clock clock;

    @Operation(summary = "列表：下单日期倒序；关键字是采购单号、供应商、物料编码，一次查几个时单号、编码精确查")
    @GetMapping
    @PreAuthorize("hasAuthority('purchase:order:list')")
    public R<PageResult<PurchaseRowVO>> page(@Valid PurchaseQuery query) {
        return R.ok(purchaseOrderService.page(query));
    }

    @Operation(summary = "详情：明细带档案现价、来源算料单和订单，入库单、出库单")
    @GetMapping("/{id:\\d+}")
    @PreAuthorize("hasAuthority('purchase:order:list')")
    public R<PurchaseDetailVO> detail(@PathVariable("id") long id) {
        return R.ok(purchaseOrderService.detail(id));
    }

    @Operation(summary = "表单默认值：供应商的结算方式、税率、采购员，各行的默认单价，交货日期")
    @GetMapping("/defaults")
    @PreAuthorize("hasAnyAuthority('purchase:order:add', 'purchase:order:edit')")
    public R<PurchaseDefaultsVO> defaults(@Valid PurchaseDefaultsQuery query) {
        return R.ok(purchaseOrderService.defaults(query));
    }

    @Operation(summary = "新建草稿，返回 id、单号、version")
    @PostMapping
    @PreAuthorize("hasAuthority('purchase:order:add')")
    @OperLog(module = "采购单", action = "新建")
    public R<DocSaved> create(@Valid @RequestBody PurchaseCreateReq req) {
        return R.ok(purchaseOrderService.create(req));
    }

    @Operation(summary = "保存草稿：整单覆盖，返回新的 version")
    @PutMapping("/{id:\\d+}")
    @PreAuthorize("hasAuthority('purchase:order:edit')")
    @OperLog(module = "采购单", action = "保存")
    public R<Integer> update(@PathVariable("id") long id, @Valid @RequestBody PurchaseSaveReq req) {
        return R.ok(purchaseOrderService.update(id, req));
    }

    @Operation(summary = "删草稿")
    @DeleteMapping("/{id:\\d+}")
    @PreAuthorize("hasAuthority('purchase:order:remove')")
    @OperLog(module = "采购单", action = "删除")
    public R<Void> delete(@PathVariable("id") long id) {
        purchaseOrderService.delete(id);
        return R.ok();
    }

    // ---------- 状态动作 ----------

    @Operation(summary = "提交：再查一遍供应商、交货地点、单价，结算方式、税率必填（没过 1000，data 是清单），按金额定审几级")
    @PostMapping("/{id:\\d+}/submit")
    @PreAuthorize("hasAuthority('purchase:order:submit')")
    @OperLog(module = "采购单", action = "提交")
    public R<Void> submit(@PathVariable("id") long id, @Valid @RequestBody DocActionReq req) {
        purchaseOrderService.act(id, "submit", req);
        return R.ok();
    }

    @Operation(summary = "撤回（制单人）")
    @PostMapping("/{id:\\d+}/withdraw")
    @OperLog(module = "采购单", action = "撤回")
    public R<Void> withdraw(@PathVariable("id") long id, @Valid @RequestBody DocActionReq req) {
        purchaseOrderService.act(id, "withdraw", req);
        return R.ok();
    }

    @Operation(summary = "驳回（当前这一级的审批人），要写原因")
    @PostMapping("/{id:\\d+}/reject")
    @OperLog(module = "采购单", action = "驳回")
    public R<Void> reject(@PathVariable("id") long id, @Valid @RequestBody DocActionReq req) {
        purchaseOrderService.act(id, "reject", req);
        return R.ok();
    }

    @Operation(summary = "审核（当前这一级的审批人）；最后一级审过，改过价的行记进价格档案")
    @PostMapping("/{id:\\d+}/audit")
    @OperLog(module = "采购单", action = "审核")
    public R<Void> audit(@PathVariable("id") long id, @Valid @RequestBody DocActionReq req) {
        purchaseOrderService.act(id, "audit", req);
        return R.ok();
    }

    @Operation(summary = "反审核：有入库单、出库单，或被已确认的算料单用作在途，1005；审核时记的价格档案作废")
    @PostMapping("/{id:\\d+}/unaudit")
    @PreAuthorize("hasAuthority('purchase:order:audit')")
    @OperLog(module = "采购单", action = "反审核")
    public R<Void> unaudit(@PathVariable("id") long id, @Valid @RequestBody DocActionReq req) {
        purchaseOrderService.act(id, "unaudit", req);
        return R.ok();
    }

    @Operation(summary = "结案：还有没审的入库单 1000")
    @PostMapping("/{id:\\d+}/close")
    @PreAuthorize("hasAuthority('purchase:order:close')")
    @OperLog(module = "采购单", action = "结案")
    public R<Void> close(@PathVariable("id") long id, @Valid @RequestBody DocActionReq req) {
        purchaseOrderService.act(id, "close", req);
        return R.ok();
    }

    @Operation(summary = "取消结案：按到货回到已审核、部分到货或已到齐")
    @PostMapping("/{id:\\d+}/reopen")
    @PreAuthorize("hasAuthority('purchase:order:close')")
    @OperLog(module = "采购单", action = "取消结案")
    public R<Void> reopen(@PathVariable("id") long id, @Valid @RequestBody DocActionReq req) {
        purchaseOrderService.act(id, "reopen", req);
        return R.ok();
    }

    @Operation(summary = "作废（待审核）：来源算料行的已转采购退回去")
    @PostMapping("/{id:\\d+}/void")
    @PreAuthorize("hasAuthority('purchase:order:void')")
    @OperLog(module = "采购单", action = "作废")
    public R<Void> voidDoc(@PathVariable("id") long id, @Valid @RequestBody DocActionReq req) {
        purchaseOrderService.act(id, "void", req);
        return R.ok();
    }

    // ---------- 生成、跟进、导出 ----------

    @Operation(summary = "算料单勾的行 → 按供应商一家一张草稿；算料单是草稿的先确认")
    @PostMapping("/from-mrp")
    @PreAuthorize("hasAuthority('purchase:order:add')")
    @OperLog(module = "采购单", action = "由算料单生成")
    public R<List<DocSaved>> fromMrp(@Valid @RequestBody FromMrpReq req) {
        return R.ok(purchaseOrderService.fromMrp(req));
    }

    @Operation(summary = "补货清单勾的行 → 按供应商一家一张备货采购单草稿")
    @PostMapping("/from-reorder")
    @PreAuthorize("hasAuthority('purchase:order:add')")
    @OperLog(module = "采购单", action = "由补货提醒生成")
    public R<List<DocSaved>> fromReorder(@Valid @RequestBody FromReorderReq req) {
        return R.ok(purchaseOrderService.fromReorder(req));
    }

    @Operation(summary = "改一行的预计到货、跟进备注（已审核、部分到货），不改版本号")
    @PutMapping("/{id:\\d+}/items/{itemId:\\d+}/follow")
    @PreAuthorize("hasAuthority('purchase:order:edit')")
    @OperLog(module = "采购单", action = "跟进")
    public R<Void> follow(@PathVariable("id") long id, @PathVariable("itemId") long itemId, @Valid @RequestBody FollowReq req) {
        purchaseOrderService.follow(id, itemId, req);
        return R.ok();
    }

    @Operation(summary = "导出：和列表一样的条件，一条明细一行")
    @GetMapping("/export")
    @PreAuthorize("hasAuthority('purchase:order:export')")
    public ResponseEntity<byte[]> export(@Valid PurchaseQuery query) {
        return Excel.download(Excel.exportFileName("采购单", LocalDate.now(clock)), purchaseOrderService.export(query));
    }

    // ---------- 待到料 ----------

    @Operation(summary = "待到料：没入齐的采购行，按哪天到排；页签 LATE 已逾期 / NEAR 3 天内 / CUT 晚于开裁")
    @GetMapping("/pending-items")
    @PreAuthorize("hasAuthority('purchase:order:list')")
    public R<PageResult<PendingArrivalRowVO>> pendingItems(@Valid PendingArrivalQuery query) {
        return R.ok(purchaseOrderService.pendingItems(query));
    }

    @Operation(summary = "待到料页签上的数：条件和列表一样，不看页签")
    @GetMapping("/pending-items/counts")
    @PreAuthorize("hasAuthority('purchase:order:list')")
    public R<PendingArrivalCounts> pendingCounts(@Valid PendingArrivalQuery query) {
        return R.ok(purchaseOrderService.pendingCounts(query));
    }

    // ---------- 单据公共接口（README §4.3） ----------

    @Operation(summary = "操作记录，新的在前")
    @GetMapping("/{id:\\d+}/logs")
    @PreAuthorize("hasAuthority('purchase:order:list')")
    public R<List<DocLogVO>> logs(@PathVariable("id") long id) {
        return R.ok(docCommonService.logs(BizType.PURCHASE_ORDER, id, purchaseOrderService::checkVisible));
    }

    @Operation(summary = "附件")
    @GetMapping("/{id:\\d+}/attachments")
    @PreAuthorize("hasAuthority('purchase:order:list')")
    public R<List<AttachmentVO>> attachments(@PathVariable("id") long id) {
        return R.ok(docCommonService.attachments(BizType.PURCHASE_ORDER, id, purchaseOrderService::checkVisible));
    }

    @Operation(summary = "附件整组替换（任何状态都能改）")
    @PutMapping("/{id:\\d+}/attachments")
    @PreAuthorize("hasAuthority('purchase:order:edit')")
    @OperLog(module = "采购单", action = "换附件")
    public R<List<AttachmentVO>> replaceAttachments(@PathVariable("id") long id, @RequestBody List<@Valid AttachmentReq> files) {
        return R.ok(docCommonService.replaceAttachments(BizType.PURCHASE_ORDER, id, files, purchaseOrderService::checkVisible));
    }

    @Operation(summary = "下游单据：入库单、出库单，把它用作在途的已确认算料单")
    @GetMapping("/{id:\\d+}/references")
    @PreAuthorize("hasAuthority('purchase:order:list')")
    public R<List<DocRef>> references(@PathVariable("id") long id) {
        return R.ok(docCommonService.references(BizType.PURCHASE_ORDER, id, purchaseOrderService::checkVisible));
    }

    @Operation(summary = "上游变更提醒：订单变更审过以后插的")
    @GetMapping("/{id:\\d+}/notices")
    @PreAuthorize("hasAuthority('purchase:order:list')")
    public R<List<DocNoticeVO>> notices(@PathVariable("id") long id) {
        return R.ok(docCommonService.notices(BizType.PURCHASE_ORDER, id, purchaseOrderService::checkVisible));
    }

    @Operation(summary = "处理一条上游变更提醒")
    @PostMapping("/{id:\\d+}/notices/{noticeId:\\d+}/handle")
    @PreAuthorize("hasAuthority('purchase:order:edit')")
    public R<Void> handleNotice(@PathVariable("id") long id, @PathVariable("noticeId") long noticeId,
                                @Valid @RequestBody NoticeHandleReq req) {
        docCommonService.handleNotice(BizType.PURCHASE_ORDER, id, noticeId, req.getRemark(), purchaseOrderService::checkVisible);
        return R.ok();
    }
}
