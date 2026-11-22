package com.lingmao.scm.module.production.controller;

import com.lingmao.scm.common.idempotency.IdempotentCreate;
import com.lingmao.scm.common.idempotency.IdempotencyReplayGuard;
import tools.jackson.databind.JsonNode;

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
import com.lingmao.scm.module.biz.dto.NoticeHandleReq;
import com.lingmao.scm.module.biz.entity.BizType;
import com.lingmao.scm.module.biz.service.DocCommonService;
import com.lingmao.scm.module.production.dto.IssueCloseReq;
import com.lingmao.scm.module.production.dto.IssueCreated;
import com.lingmao.scm.module.production.dto.IssueOptionQuery;
import com.lingmao.scm.module.production.dto.IssueOptionVO;
import com.lingmao.scm.module.production.dto.IssueReq;
import com.lingmao.scm.module.production.dto.MobileOrderRow;
import com.lingmao.scm.module.production.dto.NodePlanReq;
import com.lingmao.scm.module.production.dto.NodeReportReq;
import com.lingmao.scm.module.production.dto.NodeReportResult;
import com.lingmao.scm.module.production.dto.NodeReportVO;
import com.lingmao.scm.module.production.dto.ProductionDetailVO;
import com.lingmao.scm.module.production.dto.ProductionIssueVO;
import com.lingmao.scm.module.production.dto.ProductionMaterialsVO;
import com.lingmao.scm.module.production.dto.ProductionQuery;
import com.lingmao.scm.module.production.dto.ProductionReceiptVO;
import com.lingmao.scm.module.production.dto.ProductionRowVO;
import com.lingmao.scm.module.production.dto.ProductionSaveReq;
import com.lingmao.scm.module.production.dto.ReasonReq;
import com.lingmao.scm.module.production.dto.ReceiptCreated;
import com.lingmao.scm.module.production.dto.ReceiptReq;
import com.lingmao.scm.module.production.dto.SchedulePrefillVO;
import com.lingmao.scm.module.production.dto.ScheduleQuoteVO;
import com.lingmao.scm.module.production.dto.ScheduleReq;
import com.lingmao.scm.module.production.entity.ProductionKind;
import com.lingmao.scm.module.production.service.ProductionIssueService;
import com.lingmao.scm.module.production.service.ProductionNodeService;
import com.lingmao.scm.module.production.service.ProductionOrderService;
import com.lingmao.scm.module.production.service.ProductionReceiptService;
import com.lingmao.scm.module.production.service.ScheduleService;
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

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 外发加工单、成品采购单（docs/api/05 §3.2–3.6、§3.8）和排单（02 §3.7）。两种单共用一组接口和权限码 production:order:*，列表按 kind 分；
 * 审核、驳回只要登录，谁能审由多级审批按当前这一级判断（Service 里查），撤回只给制单人。
 * 领料、退料在库存那边开单（inventory:doc:add），这里只给领料页签和选生产单
 */
@Tag(name = "生产单")
@RestController
@RequestMapping("/api/production-orders")
@RequiredArgsConstructor
public class ProductionOrderController extends IdempotencyReplayGuard {

    /** 聚合查询开关仅由服务端构造，不能通过 GET 参数触发旁路或改变导出/COUNT 口径。 */
    @org.springframework.web.bind.annotation.InitBinder("productionQuery")
    void protectInternalQueryFields(org.springframework.web.bind.WebDataBinder binder) {
        binder.setDisallowedFields("cutRiskFirst", "oldestFirst", "delayedFirst", "dueFirst", "factoryIds*", "orderNos*", "approvalIds*", "approvalScope*");
    }

    private final ProductionOrderService productionOrderService;
    private final ScheduleService scheduleService;
    private final ProductionNodeService nodeService;
    private final ProductionIssueService issueService;
    private final ProductionReceiptService receiptService;
    private final DocCommonService docCommonService;
    private final Clock clock;

    @Operation(summary = "列表：kind 必传；按风险（逾期 > 节点延期 > 有异常 > 临期）、交期排")
    @GetMapping
    @PreAuthorize("hasAuthority('production:order:list')")
    public R<PageResult<ProductionRowVO>> page(@Valid @org.springframework.web.bind.annotation.ModelAttribute("productionQuery") ProductionQuery query) {
        return R.ok(productionOrderService.page(query));
    }

    @Operation(summary = "状态页签上的数：条件和列表一样，不看 status、returned；PARTIAL 是生产中里回过货的")
    @GetMapping("/counts")
    @PreAuthorize("hasAuthority('production:order:list')")
    public R<Map<String, Long>> counts(@Valid @org.springframework.web.bind.annotation.ModelAttribute("productionQuery") ProductionQuery query) {
        return R.ok(productionOrderService.counts(query));
    }

    @Operation(summary = "详情：明细、节点（带最新照片）、档案现价、风险；手机报工点进一张单也用它")
    @GetMapping("/{id:\\d+}")
    @PreAuthorize("hasAnyAuthority('production:order:list', 'production:mobile:list')")
    public R<ProductionDetailVO> detail(@PathVariable("id") long id) {
        return R.ok(productionOrderService.detail(id));
    }

    @Operation(summary = "改草稿：只改单头，件数要改就删了回订单重排；返回新的 version")
    @PutMapping("/{id:\\d+}")
    @PreAuthorize("hasAuthority('production:order:edit')")
    @OperLog(module = "生产单", action = "保存")
    public R<Integer> update(@PathVariable("id") long id, @Valid @RequestBody ProductionSaveReq req) {
        return R.ok(productionOrderService.update(id, req));
    }

    @Operation(summary = "删草稿：件数退回订单的待排单")
    @DeleteMapping("/{id:\\d+}")
    @PreAuthorize("hasAuthority('production:order:remove')")
    @OperLog(module = "生产单", action = "删除")
    public R<Void> delete(@PathVariable("id") long id) {
        productionOrderService.delete(id);
        return R.ok();
    }

    // ---------- 状态动作 ----------

    @Operation(summary = "提交：加工厂还是合作中、单价和改价原因（没过 1000，data 是清单），按总金额定审几级")
    @PostMapping("/{id:\\d+}/submit")
    @PreAuthorize("hasAuthority('production:order:submit')")
    @OperLog(module = "生产单", action = "提交")
    public R<Void> submit(@PathVariable("id") long id, @Valid @RequestBody DocActionReq req) {
        productionOrderService.act(id, "submit", req);
        return R.ok();
    }

    @Operation(summary = "撤回（制单人）")
    @PostMapping("/{id:\\d+}/withdraw")
    @OperLog(module = "生产单", action = "撤回")
    public R<Void> withdraw(@PathVariable("id") long id, @Valid @RequestBody DocActionReq req) {
        productionOrderService.act(id, "withdraw", req);
        return R.ok();
    }

    @Operation(summary = "驳回（当前这一级的审批人），要写原因")
    @PostMapping("/{id:\\d+}/reject")
    @OperLog(module = "生产单", action = "驳回")
    public R<Void> reject(@PathVariable("id") long id, @Valid @RequestBody DocActionReq req) {
        productionOrderService.act(id, "reject", req);
        return R.ok();
    }

    @Operation(summary = "审核（当前这一级的审批人）；最后一级审过：生成节点、改过价的记进价格档案、订单变生产中")
    @PostMapping("/{id:\\d+}/audit")
    @OperLog(module = "生产单", action = "审核")
    public R<Void> audit(@PathVariable("id") long id, @Valid @RequestBody DocActionReq req) {
        productionOrderService.act(id, "audit", req);
        return R.ok();
    }

    @Operation(summary = "反审核：报过进度 1000；有领料退料单、回货单、异常 1005；删节点、价格档案作废")
    @PostMapping("/{id:\\d+}/unaudit")
    @PreAuthorize("hasAuthority('production:order:audit')")
    @OperLog(module = "生产单", action = "反审核")
    public R<Void> unaudit(@PathVariable("id") long id, @Valid @RequestBody DocActionReq req) {
        productionOrderService.act(id, "unaudit", req);
        return R.ok();
    }

    @Operation(summary = "结案：已下单、生产中、已完成都能结")
    @PostMapping("/{id:\\d+}/close")
    @PreAuthorize("hasAuthority('production:order:close')")
    @OperLog(module = "生产单", action = "结案")
    public R<Void> close(@PathVariable("id") long id, @Valid @RequestBody DocActionReq req) {
        productionOrderService.act(id, "close", req);
        return R.ok();
    }

    @Operation(summary = "取消结案：按进度回到已下单、生产中或已完成")
    @PostMapping("/{id:\\d+}/reopen")
    @PreAuthorize("hasAuthority('production:order:close')")
    @OperLog(module = "生产单", action = "取消结案")
    public R<Void> reopen(@PathVariable("id") long id, @Valid @RequestBody DocActionReq req) {
        productionOrderService.act(id, "reopen", req);
        return R.ok();
    }

    @Operation(summary = "作废（草稿、待审核）：件数退回订单的待排单")
    @PostMapping("/{id:\\d+}/void")
    @PreAuthorize("hasAuthority('production:order:void')")
    @OperLog(module = "生产单", action = "作废")
    public R<Void> voidDoc(@PathVariable("id") long id, @Valid @RequestBody DocActionReq req) {
        productionOrderService.act(id, "void", req);
        return R.ok();
    }

    @Operation(summary = "导出：和列表一样的条件，加 ids；一张单一行")
    @GetMapping("/export")
    @PreAuthorize("hasAuthority('production:order:export')")
    public ResponseEntity<byte[]> export(@Valid @org.springframework.web.bind.annotation.ModelAttribute("productionQuery") ProductionQuery query) {
        byte[] data = productionOrderService.export(query);
        String name = ProductionKind.valueOf(query.getKind().strip()).getLabel();
        return Excel.download(Excel.exportFileName(name, LocalDate.now(clock)), data);
    }

    // ---------- 排单（界面在订单详情，02 §3.7） ----------

    @Operation(summary = "排单对话框带出：还没排满的款、SKU，默认加工厂、交期、跟单")
    @GetMapping("/schedule-prefill")
    @PreAuthorize("hasAuthority('production:order:add')")
    public R<SchedulePrefillVO> schedulePrefill(@RequestParam("orderId") long orderId) {
        return R.ok(scheduleService.prefill(orderId));
    }

    @Operation(summary = "选了加工厂带出各加工方式的档案现价、结算方式、税率；改草稿换加工厂也用")
    @GetMapping("/schedule-quote")
    @PreAuthorize("hasAnyAuthority('production:order:add', 'production:order:edit')")
    public R<ScheduleQuoteVO> scheduleQuote(@RequestParam("orderId") long orderId, @RequestParam("styleId") long styleId,
                                            @RequestParam("factoryId") long factoryId) {
        return R.ok(scheduleService.quote(orderId, styleId, factoryId));
    }

    @Operation(summary = "排单：一块一张草稿；超排 1004（data 是超了的 SKU），别的问题 1000（data 是清单）")
    @PostMapping("/schedule")
    @PreAuthorize("hasAuthority('production:order:add')")
    @OperLog(module = "生产单", action = "排单")
    @IdempotentCreate("PRODUCTION_SCHEDULE")
    public R<List<DocRef>> schedule(@Valid @RequestBody ScheduleReq req) {
        return R.ok(scheduleService.schedule(req));
    }

    // ---------- 进度节点 ----------

    @Operation(summary = "改节点计划（已下单、生产中）：全部节点一起发")
    @PutMapping("/{id:\\d+}/nodes")
    @PreAuthorize("hasAuthority('production:node:edit')")
    @OperLog(module = "生产单", action = "改节点计划")
    public R<Map<String, Integer>> updatePlan(@PathVariable("id") long id, @Valid @RequestBody NodePlanReq req) {
        return R.ok(Map.of("planRevision", nodeService.updatePlan(id, req)));
    }

    @Operation(summary = "报进度（电脑、手机共用）：报开始 / 报完成；前面还有节点没报完成时照样报上，返回 warning")
    @PostMapping("/{id:\\d+}/nodes/{nodeId:\\d+}/reports")
    @PreAuthorize("hasAuthority('production:node:edit')")
    @OperLog(module = "生产单", action = "报进度")
    @IdempotentCreate("PRODUCTION_NODE_REPORT")
    public R<NodeReportResult> report(@PathVariable("id") long id, @PathVariable("nodeId") long nodeId,
                                      @Valid @RequestBody NodeReportReq req) {
        return R.ok(nodeService.report(id, nodeId, req));
    }

    @Operation(summary = "一个节点的汇报记录，新的在前")
    @GetMapping("/{id:\\d+}/nodes/{nodeId:\\d+}/reports")
    @PreAuthorize("hasAnyAuthority('production:order:list', 'production:mobile:list')")
    public R<List<NodeReportVO>> nodeReports(@PathVariable("id") long id, @PathVariable("nodeId") long nodeId) {
        return R.ok(nodeService.reports(id, nodeId));
    }

    // ---------- 领料 ----------

    @Operation(summary = "领料页签：应领、已领、还差（只算我方采购的料）、物料仓库存，挂在这张单上的领料退料单")
    @GetMapping("/{id:\\d+}/materials")
    @PreAuthorize("hasAuthority('production:order:list')")
    public R<ProductionMaterialsVO> materials(@PathVariable("id") long id) {
        return R.ok(productionOrderService.materials(id));
    }

    @Operation(summary = "给库存选生产单：领料列还有没领齐的，returnable = true 列已领 > 0 的")
    @GetMapping("/issue-options")
    @PreAuthorize("hasAuthority('inventory:doc:add')")
    public R<PageResult<IssueOptionVO>> issueOptions(@Valid IssueOptionQuery query) {
        return R.ok(productionOrderService.issueOptions(query));
    }

    // ---------- 异常 ----------

    @Operation(summary = "异常列表：没关闭的在前")
    @GetMapping("/{id:\\d+}/issues")
    @PreAuthorize("hasAnyAuthority('production:order:list', 'production:mobile:list')")
    public R<List<ProductionIssueVO>> issues(@PathVariable("id") long id) {
        return R.ok(issueService.list(id));
    }

    @Operation(summary = "上报异常（电脑、手机共用）：照片必传；影响交期的改预计交期")
    @PostMapping("/{id:\\d+}/issues")
    @PreAuthorize("hasAuthority('production:issue:edit')")
    @OperLog(module = "生产单", action = "上报异常")
    @IdempotentCreate("PRODUCTION_ISSUE_CREATE")
    public R<IssueCreated> reportIssue(@PathVariable("id") long id, @Valid @RequestBody IssueReq req) {
        return R.ok(issueService.report(id, req));
    }

    @Operation(summary = "开始处理：待处理 → 处理中，处理人记我")
    @PostMapping("/{id:\\d+}/issues/{issueId:\\d+}/start")
    @PreAuthorize("hasAuthority('production:issue:edit')")
    @OperLog(module = "生产单", action = "开始处理异常")
    public R<Void> startIssue(@PathVariable("id") long id, @PathVariable("issueId") long issueId) {
        issueService.start(id, issueId);
        return R.ok();
    }

    @Operation(summary = "关闭异常：处理措施必填")
    @PostMapping("/{id:\\d+}/issues/{issueId:\\d+}/close")
    @PreAuthorize("hasAuthority('production:issue:edit')")
    @OperLog(module = "生产单", action = "关闭异常")
    public R<Void> closeIssue(@PathVariable("id") long id, @PathVariable("issueId") long issueId,
                              @Valid @RequestBody IssueCloseReq req) {
        issueService.close(id, issueId, req);
        return R.ok();
    }

    // ---------- 回货 ----------

    @Operation(summary = "回货单（含作废的），新的在前")
    @GetMapping("/{id:\\d+}/receipts")
    @PreAuthorize("hasAuthority('production:order:list')")
    public R<List<ProductionReceiptVO>> receipts(@PathVariable("id") long id) {
        return R.ok(receiptService.list(id));
    }

    @Operation(summary = "登记回货（建 + 审核一步）：超过件数的 103% 1004（data 是超了的 SKU）")
    @PostMapping("/{id:\\d+}/receipts")
    @PreAuthorize("hasAuthority('production:receipt:add')")
    @OperLog(module = "生产单", action = "登记回货")
    public R<ReceiptCreated> receive(@PathVariable("id") long id, @Valid @RequestBody ReceiptReq req) {
        return R.ok(receiptService.receive(id, req));
    }

    @Operation(summary = "作废回货单：已回、次品减回去，生产单、订单的状态重算")
    @PostMapping("/{id:\\d+}/receipts/{receiptId:\\d+}/void")
    @PreAuthorize("hasAuthority('production:receipt:void')")
    @OperLog(module = "生产单", action = "作废回货单")
    public R<Void> voidReceipt(@PathVariable("id") long id, @PathVariable("receiptId") long receiptId,
                               @Valid @RequestBody ReasonReq req) {
        receiptService.voidReceipt(id, receiptId, req.getReason());
        return R.ok();
    }

    // ---------- 手机报工 ----------

    @Operation(summary = "我的跟单：生产跟单是我、已下单或生产中，按风险排，不分页")
    @GetMapping("/my-follow")
    @PreAuthorize("hasAuthority('production:mobile:list')")
    public R<List<MobileOrderRow>> myFollow() {
        return R.ok(productionOrderService.myFollow());
    }

    // ---------- 单据公共接口（README §4.3） ----------

    @Operation(summary = "操作记录，新的在前")
    @GetMapping("/{id:\\d+}/logs")
    @PreAuthorize("hasAuthority('production:order:list')")
    public R<List<DocLogVO>> logs(@PathVariable("id") long id) {
        return R.ok(docCommonService.logs(BizType.PRODUCTION_ORDER, id, productionOrderService::checkVisible));
    }

    @Operation(summary = "附件")
    @GetMapping("/{id:\\d+}/attachments")
    @PreAuthorize("hasAuthority('production:order:list')")
    public R<List<AttachmentVO>> attachments(@PathVariable("id") long id) {
        return R.ok(docCommonService.attachments(BizType.PRODUCTION_ORDER, id, productionOrderService::checkVisible));
    }

    @Operation(summary = "附件整组替换（任何状态都能改）")
    @PutMapping("/{id:\\d+}/attachments")
    @PreAuthorize("hasAuthority('production:order:edit')")
    @OperLog(module = "生产单", action = "换附件")
    public R<List<AttachmentVO>> replaceAttachments(@PathVariable("id") long id, @RequestBody List<@Valid AttachmentReq> files) {
        return R.ok(docCommonService.replaceAttachments(BizType.PRODUCTION_ORDER, id, files, productionOrderService::checkVisible));
    }

    @Operation(summary = "下游单据：领料出库单、退料入库单（草稿也列）、没作废的回货单、异常")
    @GetMapping("/{id:\\d+}/references")
    @PreAuthorize("hasAuthority('production:order:list')")
    public R<List<DocRef>> references(@PathVariable("id") long id) {
        return R.ok(docCommonService.references(BizType.PRODUCTION_ORDER, id, productionOrderService::checkVisible));
    }

    @Operation(summary = "上游变更提醒：订单变更审过以后插的")
    @GetMapping("/{id:\\d+}/notices")
    @PreAuthorize("hasAuthority('production:order:list')")
    public R<List<DocNoticeVO>> notices(@PathVariable("id") long id) {
        return R.ok(docCommonService.notices(BizType.PRODUCTION_ORDER, id, productionOrderService::checkVisible));
    }

    @Operation(summary = "处理一条上游变更提醒")
    @PostMapping("/{id:\\d+}/notices/{noticeId:\\d+}/handle")
    @PreAuthorize("hasAuthority('production:order:edit')")
    public R<Void> handleNotice(@PathVariable("id") long id, @PathVariable("noticeId") long noticeId,
                                @Valid @RequestBody NoticeHandleReq req) {
        docCommonService.handleNotice(BizType.PRODUCTION_ORDER, id, noticeId, req.getRemark(), productionOrderService::checkVisible);
        return R.ok();
    }

    /** 重放只读检查当前对象范围，不能因为曾经成功就绕过之后的降权。 */
    @Override
    public void authorizeReplay(String operation, Object[] arguments, JsonNode result) {
        if (!"PRODUCTION_SCHEDULE".equals(operation)) {
            productionOrderService.checkVisible(((Number) arguments[0]).longValue());
            return;
        }
        scheduleService.checkSourceOrderVisible(((ScheduleReq) arguments[0]).getOrderId());
        if (result.isArray()) {
            result.forEach(row -> productionOrderService.checkVisible(row.get("id").longValue()));
        } else {
            productionOrderService.checkVisible(result.get("id").longValue());
        }
    }
}
