package com.lingmao.scm.module.fg.controller;

import com.lingmao.scm.common.excel.Excel;
import com.lingmao.scm.common.excel.ImportResult;
import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.common.idempotency.IdempotencyReplayGuard;
import com.lingmao.scm.common.idempotency.IdempotentCreate;
import com.lingmao.scm.common.log.OperLog;
import com.lingmao.scm.common.page.PageResult;
import com.lingmao.scm.common.result.R;
import com.lingmao.scm.module.biz.dto.AttachmentReq;
import com.lingmao.scm.module.biz.dto.AttachmentVO;
import com.lingmao.scm.module.biz.dto.DocActionReq;
import com.lingmao.scm.module.biz.dto.DocLogVO;
import com.lingmao.scm.module.biz.dto.DocRef;
import com.lingmao.scm.module.biz.entity.BizType;
import com.lingmao.scm.module.biz.service.DocCommonService;
import com.lingmao.scm.module.fg.dto.FgDocCreateReq;
import com.lingmao.scm.module.fg.dto.FgDocCreatedVO;
import com.lingmao.scm.module.fg.dto.FgDocDetailVO;
import com.lingmao.scm.module.fg.dto.FgDocQuery;
import com.lingmao.scm.module.fg.dto.FgDocRowVO;
import com.lingmao.scm.module.fg.dto.FgDocSaveReq;
import com.lingmao.scm.module.fg.dto.FgFromReceiptReq;
import com.lingmao.scm.module.fg.dto.FgPendingReceiptQuery;
import com.lingmao.scm.module.fg.dto.FgPendingReceiptRowVO;
import com.lingmao.scm.module.fg.dto.FgReceiptDraftVO;
import com.lingmao.scm.module.fg.dto.FgReceiptStockedVO;
import com.lingmao.scm.module.fg.dto.FgShipOrderOptionVO;
import com.lingmao.scm.module.fg.dto.FgShipPrefillVO;
import com.lingmao.scm.module.fg.dto.FgShippedRow;
import com.lingmao.scm.module.fg.dto.FgStyleOptionVO;
import com.lingmao.scm.module.fg.entity.FgDocKind;
import com.lingmao.scm.module.fg.service.FgDocService;
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
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.JsonNode;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

/** 成品入库单、成品出库单（docs/api/07 §3.2–§3.4、§3.7）：一组接口，列表按 kind 分 */
@Tag(name = "成品入库单、出库单")
@RestController
@RequestMapping("/api/fg-docs")
@RequiredArgsConstructor
public class FgDocController extends IdempotencyReplayGuard {

    private final FgDocService fgDocService;
    private final DocCommonService docCommonService;
    private final Clock clock;

    @Operation(summary = "列表（kind 必填），新的在前")
    @GetMapping
    @PreAuthorize("hasAuthority('fg:doc:list')")
    public R<PageResult<FgDocRowVO>> page(@Valid FgDocQuery query) {
        return R.ok(fgDocService.page(query));
    }

    @Operation(summary = "详情；草稿带每行最多能填多少，出库草稿带出仓现在的库存")
    @GetMapping("/{id:\\d+}")
    @PreAuthorize("hasAuthority('fg:doc:list')")
    public R<FgDocDetailVO> detail(@PathVariable("id") long id) {
        return R.ok(fgDocService.detail(id));
    }

    @Operation(summary = "手工新建草稿：其他入库、发货出库、其他出库")
    @PostMapping
    @PreAuthorize("hasAuthority('fg:doc:add')")
    @OperLog(module = "成品出入库单", action = "新建")
    @IdempotentCreate("FG_CREATE")
    public R<FgDocCreatedVO> create(@Valid @RequestBody FgDocCreateReq req) {
        return R.ok(fgDocService.create(req));
    }

    @Operation(summary = "改草稿，整体覆盖；返回新的 version")
    @PutMapping("/{id:\\d+}")
    @PreAuthorize("hasAuthority('fg:doc:edit')")
    @OperLog(module = "成品出入库单", action = "保存")
    public R<Integer> update(@PathVariable("id") long id, @Valid @RequestBody FgDocSaveReq req) {
        return R.ok(fgDocService.update(id, req));
    }

    @Operation(summary = "删草稿（过过账的只能作废）")
    @DeleteMapping("/{id:\\d+}")
    @PreAuthorize("hasAuthority('fg:doc:remove')")
    @OperLog(module = "成品出入库单", action = "删除")
    public R<Void> delete(@PathVariable("id") long id) {
        fgDocService.delete(id);
        return R.ok();
    }

    @Operation(summary = "审核过账（库存不够 1001，超上限 1004）")
    @PostMapping("/{id:\\d+}/audit")
    @PreAuthorize("hasAuthority('fg:doc:audit')")
    @OperLog(module = "成品出入库单", action = "审核过账")
    public R<Void> audit(@PathVariable("id") long id, @Valid @RequestBody DocActionReq req) {
        fgDocService.act(id, "audit", req);
        return R.ok();
    }

    @Operation(summary = "反审核：库存改回去（改回去是负的 1001），回到草稿")
    @PostMapping("/{id:\\d+}/unaudit")
    @PreAuthorize("hasAuthority('fg:doc:audit')")
    @OperLog(module = "成品出入库单", action = "反审核")
    public R<Void> unaudit(@PathVariable("id") long id, @Valid @RequestBody DocActionReq req) {
        fgDocService.act(id, "unaudit", req);
        return R.ok();
    }

    @Operation(summary = "作废：过过账又反审核回来的草稿（没过过账的直接删），原因必填")
    @PostMapping("/{id:\\d+}/void")
    @PreAuthorize("hasAuthority('fg:doc:remove')")
    @OperLog(module = "成品出入库单", action = "作废")
    public R<Void> voidDoc(@PathVariable("id") long id, @Valid @RequestBody DocActionReq req) {
        fgDocService.act(id, "void", req);
        return R.ok();
    }

    // ---------- 回货入库 ----------

    @Operation(summary = "待入库：已登记、还有良品没入的回货单（只列启用成品模块以后登记的）")
    @GetMapping("/pending-receipts")
    @PreAuthorize("hasAuthority('fg:doc:add')")
    public R<PageResult<FgPendingReceiptRowVO>> pendingReceipts(@Valid FgPendingReceiptQuery query) {
        return R.ok(fgDocService.pendingReceipts(query));
    }

    @Operation(summary = "从回货单带出：每行还能入多少（不存库）")
    @GetMapping("/from-receipt/{receiptId:\\d+}")
    @PreAuthorize("hasAuthority('fg:doc:add')")
    public R<FgReceiptDraftVO> prefillFromReceipt(@PathVariable("receiptId") long receiptId) {
        return R.ok(fgDocService.receiptDraft(receiptId));
    }

    @Operation(summary = "由回货单生成成品入库单（post = true 时马上过账，要审核权限）")
    @PostMapping("/from-receipt/{receiptId:\\d+}")
    @PreAuthorize("hasAuthority('fg:doc:add')")
    @OperLog(module = "成品出入库单", action = "由回货单生成")
    public R<FgDocCreatedVO> fromReceipt(@PathVariable("receiptId") long receiptId, @Valid @RequestBody FgFromReceiptReq req) {
        // 建好马上过账要 :add 和 :audit 两个权限（docs/api/07 §3.3）
        if (req.isPost() && !LoginUser.hasAuthority("fg:doc:audit")) {
            throw new BizException(403, "建好马上入库要有审核权限：先存草稿，让有权限的人审核");
        }
        return R.ok(fgDocService.fromReceipt(receiptId, req));
    }

    // ---------- 发货出库 ----------

    @Operation(summary = "能发货的需求订单（下拉，最多 50 条）")
    @GetMapping("/ship-orders")
    @PreAuthorize("hasAuthority('fg:doc:add')")
    public R<List<FgShipOrderOptionVO>> shipOrders(@RequestParam(value = "keyword", required = false) String keyword,
                                                   @Parameter(description = "给了就带这个仓里订单这些 SKU 的库存合计")
                                                   @RequestParam(value = "warehouseId", required = false) Long warehouseId) {
        return R.ok(fgDocService.shipOrders(keyword, warehouseId));
    }

    @Operation(summary = "发货带出：订单每个 SKU 的件数、已发、最多还能发、库存、单价")
    @GetMapping("/ship-prefill")
    @PreAuthorize("hasAuthority('fg:doc:add') or hasAuthority('fg:doc:edit')")
    public R<FgShipPrefillVO> shipPrefill(@RequestParam("orderId") long orderId,
                                          @RequestParam(value = "warehouseId", required = false) Long warehouseId,
                                          @Parameter(description = "改草稿时传这张单的 id：已发不算它")
                                          @RequestParam(value = "docId", required = false) Long docId) {
        return R.ok(fgDocService.shipPrefill(orderId, warehouseId, docId));
    }

    @Operation(summary = "画矩阵要的款式（颜色、尺码、SKU，停用的也在），最多 100 个")
    @GetMapping("/style-options")
    @PreAuthorize("hasAuthority('fg:doc:add') or hasAuthority('fg:doc:edit')")
    public R<List<FgStyleOptionVO>> styleOptions(@RequestParam("styleIds") List<Long> styleIds) {
        return R.ok(fgDocService.styleOptions(styleIds));
    }

    // ---------- 导入、导出 ----------

    @Operation(summary = "导入明细的模板")
    @GetMapping("/items-template")
    @PreAuthorize("hasAuthority('fg:doc:edit')")
    public ResponseEntity<byte[]> itemsTemplate() {
        return Excel.download("成品入库明细模板.xlsx", fgDocService.itemsTemplate());
    }

    @Operation(summary = "其他入库的草稿导入明细（按 SKU 码，单上有的改件数，没有的加一行）")
    @PostMapping("/{id:\\d+}/items/import")
    @PreAuthorize("hasAuthority('fg:doc:edit')")
    @OperLog(module = "成品出入库单", action = "导入明细")
    public R<ImportResult> importItems(@PathVariable("id") long id, @RequestParam("file") MultipartFile file) {
        return R.ok(fgDocService.importItems(id, file));
    }

    @Operation(summary = "导出：一行一条明细")
    @GetMapping("/export")
    @PreAuthorize("hasAuthority('fg:doc:export')")
    public ResponseEntity<byte[]> export(@Valid FgDocQuery query) {
        String name = query.getKind() == FgDocKind.IN ? "成品入库单" : "成品出库单";
        return Excel.download(Excel.exportFileName(name, LocalDate.now(clock)), fgDocService.export(query));
    }

    // ---------- 给上游页面 ----------

    @Operation(summary = "订单每个 SKU 的已发（已审核的发货出库）")
    @GetMapping("/shipped")
    @PreAuthorize("hasAuthority('fg:doc:list')")
    public R<List<FgShippedRow>> shipped(@RequestParam("orderId") long orderId) {
        return R.ok(fgDocService.shipped(orderId));
    }

    @Operation(summary = "这几张回货单的成品入库单（没作废的），最多 200 张")
    @GetMapping("/by-receipts")
    @PreAuthorize("hasAuthority('fg:doc:list')")
    public R<List<FgReceiptStockedVO>> byReceipts(@RequestParam("receiptIds") List<Long> receiptIds) {
        return R.ok(fgDocService.byReceipts(receiptIds));
    }

    // ---------- 单据公共接口（README §4.3） ----------

    @Operation(summary = "操作记录，新的在前")
    @GetMapping("/{id:\\d+}/logs")
    @PreAuthorize("hasAuthority('fg:doc:list')")
    public R<List<DocLogVO>> logs(@PathVariable("id") long id) {
        return R.ok(docCommonService.logs(BizType.FG_DOC, id, fgDocService::checkVisible));
    }

    @Operation(summary = "附件")
    @GetMapping("/{id:\\d+}/attachments")
    @PreAuthorize("hasAuthority('fg:doc:list')")
    public R<List<AttachmentVO>> attachments(@PathVariable("id") long id) {
        return R.ok(docCommonService.attachments(BizType.FG_DOC, id, fgDocService::checkVisible));
    }

    @Operation(summary = "附件整组替换")
    @PutMapping("/{id:\\d+}/attachments")
    @PreAuthorize("hasAuthority('fg:doc:edit')")
    @OperLog(module = "成品出入库单", action = "换附件")
    public R<List<AttachmentVO>> replaceAttachments(@PathVariable("id") long id, @RequestBody List<@Valid AttachmentReq> files) {
        return R.ok(docCommonService.replaceAttachments(BizType.FG_DOC, id, files, fgDocService::checkVisible));
    }

    @Operation(summary = "下游单据（成品单据没有下游，是空的）")
    @GetMapping("/{id:\\d+}/references")
    @PreAuthorize("hasAuthority('fg:doc:list')")
    public R<List<DocRef>> references(@PathVariable("id") long id) {
        return R.ok(docCommonService.references(BizType.FG_DOC, id, fgDocService::checkVisible));
    }

    /** 重放只读检查当前对象范围，不能因为曾经成功就绕过之后的降权。 */
    @Override
    public void authorizeReplay(String operation, Object[] arguments, JsonNode result) {
        fgDocService.checkVisible(result.get("id").longValue());
    }
}
