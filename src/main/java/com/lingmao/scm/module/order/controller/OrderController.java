package com.lingmao.scm.module.order.controller;

import com.lingmao.scm.common.idempotency.IdempotentCreate;
import com.lingmao.scm.common.idempotency.IdempotencyReplayGuard;
import tools.jackson.databind.JsonNode;

import com.lingmao.scm.common.excel.Excel;
import com.lingmao.scm.common.excel.ImportResult;
import com.lingmao.scm.common.log.OperLog;
import com.lingmao.scm.common.page.PageQuery;
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
import com.lingmao.scm.module.order.dto.OrderAuditReq;
import com.lingmao.scm.module.order.dto.OrderCreateReq;
import com.lingmao.scm.module.order.dto.OrderDetailVO;
import com.lingmao.scm.module.order.dto.OrderImportHead;
import com.lingmao.scm.module.order.dto.OrderQuery;
import com.lingmao.scm.module.order.dto.OrderRowVO;
import com.lingmao.scm.module.order.dto.OrderSaveReq;
import com.lingmao.scm.module.order.dto.OrderStyleOptionVO;
import com.lingmao.scm.module.order.dto.StyleOrderRowVO;
import com.lingmao.scm.module.order.service.OrderService;
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
import java.util.Map;

/**
 * 需求订单（docs/api/02 §3.1）。权限码 order:order:*；审核、驳回只要登录，谁能审由多级审批按当前这一级判断（Service 里查），
 * 撤回只给制单人（Service 里查）
 */
@Tag(name = "需求订单")
@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
public class OrderController extends IdempotencyReplayGuard {

    private final OrderService orderService;
    private final DocCommonService docCommonService;
    private final Clock clock;

    @Operation(summary = "列表：下单日期倒序；关键字是订单号、合同号、款号，一次查几个时订单号、款号精确查")
    @GetMapping
    @PreAuthorize("hasAuthority('order:order:list')")
    public R<PageResult<OrderRowVO>> page(@Valid OrderQuery query) {
        return R.ok(orderService.page(query));
    }

    @Operation(summary = "各状态几张：条件和列表一样，不看 status")
    @GetMapping("/counts")
    @PreAuthorize("hasAuthority('order:order:list')")
    public R<Map<String, Long>> counts(@Valid OrderQuery query) {
        return R.ok(orderService.counts(query));
    }

    @Operation(summary = "详情：按款分块，带锁定的和现在生效的资料包版本、没审完的变更单")
    @GetMapping("/{id:\\d+}")
    @PreAuthorize("hasAuthority('order:order:list')")
    public R<OrderDetailVO> detail(@PathVariable("id") long id) {
        return R.ok(orderService.detail(id));
    }

    @Operation(summary = "录单、提变更画矩阵要的款式信息（登录即可）：尺码、颜色、SKU、能不能下")
    @GetMapping("/style-options")
    public R<List<OrderStyleOptionVO>> styleOptions(@RequestParam("styleIds") List<Long> styleIds) {
        return R.ok(orderService.styleOptions(styleIds));
    }

    @Operation(summary = "款式详情的需求订单页签：含这个款的订单，下单日期倒序")
    @GetMapping("/by-style")
    @PreAuthorize("hasAuthority('order:order:list')")
    public R<PageResult<StyleOrderRowVO>> byStyle(@RequestParam("styleId") long styleId, @Valid PageQuery page) {
        return R.ok(orderService.byStyle(styleId, page));
    }

    @Operation(summary = "新建草稿，返回 id、单号、version")
    @PostMapping
    @PreAuthorize("hasAuthority('order:order:add')")
    @OperLog(module = "需求订单", action = "新建")
    @IdempotentCreate("ORDER_CREATE")
    public R<DocSaved> create(@Valid @RequestBody OrderCreateReq req) {
        return R.ok(orderService.create(req));
    }

    @Operation(summary = "保存草稿：整单覆盖，返回新的 version")
    @PutMapping("/{id:\\d+}")
    @PreAuthorize("hasAuthority('order:order:edit')")
    @OperLog(module = "需求订单", action = "保存")
    public R<Integer> update(@PathVariable("id") long id, @Valid @RequestBody OrderSaveReq req) {
        return R.ok(orderService.update(id, req));
    }

    @Operation(summary = "删草稿（提过变更单的只能作废）")
    @DeleteMapping("/{id:\\d+}")
    @PreAuthorize("hasAuthority('order:order:remove')")
    @OperLog(module = "需求订单", action = "删除")
    public R<Void> delete(@PathVariable("id") long id) {
        orderService.delete(id);
        return R.ok();
    }

    // ---------- 状态动作 ----------

    @Operation(summary = "提交：查款式、资料包（没过 1000，data 是清单），按金额定审几级")
    @PostMapping("/{id:\\d+}/submit")
    @PreAuthorize("hasAuthority('order:order:submit')")
    @OperLog(module = "需求订单", action = "提交")
    public R<Void> submit(@PathVariable("id") long id, @Valid @RequestBody DocActionReq req) {
        orderService.act(id, "submit", req);
        return R.ok();
    }

    @Operation(summary = "撤回（制单人）")
    @PostMapping("/{id:\\d+}/withdraw")
    @OperLog(module = "需求订单", action = "撤回")
    public R<Void> withdraw(@PathVariable("id") long id, @Valid @RequestBody DocActionReq req) {
        orderService.act(id, "withdraw", req);
        return R.ok();
    }

    @Operation(summary = "驳回（当前这一级的审批人），要写原因")
    @PostMapping("/{id:\\d+}/reject")
    @OperLog(module = "需求订单", action = "驳回")
    public R<Void> reject(@PathVariable("id") long id, @Valid @RequestBody DocActionReq req) {
        orderService.act(id, "reject", req);
        return R.ok();
    }

    @Operation(summary = "审核（当前这一级的审批人），填承诺交期；最后一级审过锁定每个款的生产 BOM")
    @PostMapping("/{id:\\d+}/audit")
    @OperLog(module = "需求订单", action = "审核")
    public R<Void> audit(@PathVariable("id") long id, @Valid @RequestBody OrderAuditReq req) {
        orderService.audit(id, req);
        return R.ok();
    }

    @Operation(summary = "反审核：有下游单据 1005，有没审完的变更单 1000")
    @PostMapping("/{id:\\d+}/unaudit")
    @PreAuthorize("hasAuthority('order:order:audit')")
    @OperLog(module = "需求订单", action = "反审核")
    public R<Void> unaudit(@PathVariable("id") long id, @Valid @RequestBody DocActionReq req) {
        orderService.act(id, "unaudit", req);
        return R.ok();
    }

    @Operation(summary = "结案")
    @PostMapping("/{id:\\d+}/close")
    @PreAuthorize("hasAuthority('order:order:audit')")
    @OperLog(module = "需求订单", action = "结案")
    public R<Void> close(@PathVariable("id") long id, @Valid @RequestBody DocActionReq req) {
        orderService.act(id, "close", req);
        return R.ok();
    }

    @Operation(summary = "取消结案：回到结案前的状态")
    @PostMapping("/{id:\\d+}/reopen")
    @PreAuthorize("hasAuthority('order:order:audit')")
    @OperLog(module = "需求订单", action = "取消结案")
    public R<Void> reopen(@PathVariable("id") long id, @Valid @RequestBody DocActionReq req) {
        orderService.act(id, "reopen", req);
        return R.ok();
    }

    @Operation(summary = "作废（草稿、待审核）")
    @PostMapping("/{id:\\d+}/void")
    @PreAuthorize("hasAuthority('order:order:void')")
    @OperLog(module = "需求订单", action = "作废")
    public R<Void> voidDoc(@PathVariable("id") long id, @Valid @RequestBody DocActionReq req) {
        orderService.act(id, "void", req);
        return R.ok();
    }

    // ---------- 导入、导出 ----------

    @Operation(summary = "导入模板")
    @GetMapping("/import-template")
    @PreAuthorize("hasAuthority('order:order:import')")
    public ResponseEntity<byte[]> importTemplate() {
        return Excel.download("需求订单导入模板.xlsx", orderService.importTemplate());
    }

    @Operation(summary = "导入：单头在查询参数里，按分单号一组一张草稿；有一处不对整批不导（1003）")
    @PostMapping("/import")
    @PreAuthorize("hasAuthority('order:order:import')")
    @OperLog(module = "需求订单", action = "导入")
    public R<ImportResult> importExcel(@Valid OrderImportHead head, @RequestParam("file") MultipartFile file) {
        return R.ok(orderService.importExcel(head, file));
    }

    @Operation(summary = "导出：和列表一样的条件，一张订单一行")
    @GetMapping("/export")
    @PreAuthorize("hasAuthority('order:order:export')")
    public ResponseEntity<byte[]> export(@Valid OrderQuery query) {
        return Excel.download(Excel.exportFileName("需求订单", LocalDate.now(clock)), orderService.export(query));
    }

    // ---------- 单据公共接口（README §4.3） ----------

    @Operation(summary = "操作记录，新的在前")
    @GetMapping("/{id:\\d+}/logs")
    @PreAuthorize("hasAuthority('order:order:list')")
    public R<List<DocLogVO>> logs(@PathVariable("id") long id) {
        return R.ok(docCommonService.logs(BizType.ORDER, id, orderService::checkVisible));
    }

    @Operation(summary = "附件")
    @GetMapping("/{id:\\d+}/attachments")
    @PreAuthorize("hasAuthority('order:order:list')")
    public R<List<AttachmentVO>> attachments(@PathVariable("id") long id) {
        return R.ok(docCommonService.attachments(BizType.ORDER, id, orderService::checkVisible));
    }

    @Operation(summary = "附件整组替换（任何状态都能改）")
    @PutMapping("/{id:\\d+}/attachments")
    @PreAuthorize("hasAuthority('order:order:edit')")
    @OperLog(module = "需求订单", action = "换附件")
    public R<List<AttachmentVO>> replaceAttachments(@PathVariable("id") long id, @RequestBody List<@Valid AttachmentReq> files) {
        return R.ok(docCommonService.replaceAttachments(BizType.ORDER, id, files, orderService::checkVisible));
    }

    @Operation(summary = "下游单据：生产单、算料单")
    @GetMapping("/{id:\\d+}/references")
    @PreAuthorize("hasAuthority('order:order:list')")
    public R<List<DocRef>> references(@PathVariable("id") long id) {
        return R.ok(docCommonService.references(BizType.ORDER, id, orderService::checkVisible));
    }

    @Operation(summary = "上游变更提醒（订单没有上游，是空的）")
    @GetMapping("/{id:\\d+}/notices")
    @PreAuthorize("hasAuthority('order:order:list')")
    public R<List<DocNoticeVO>> notices(@PathVariable("id") long id) {
        return R.ok(docCommonService.notices(BizType.ORDER, id, orderService::checkVisible));
    }

    @Operation(summary = "处理一条上游变更提醒")
    @PostMapping("/{id:\\d+}/notices/{noticeId:\\d+}/handle")
    @PreAuthorize("hasAuthority('order:order:edit')")
    public R<Void> handleNotice(@PathVariable("id") long id, @PathVariable("noticeId") long noticeId,
                                @Valid @RequestBody NoticeHandleReq req) {
        docCommonService.handleNotice(BizType.ORDER, id, noticeId, req.getRemark(), orderService::checkVisible);
        return R.ok();
    }

    /** 重放只读检查当前对象范围，不能因为曾经成功就绕过之后的降权。 */
    @Override
    public void authorizeReplay(String operation, Object[] arguments, JsonNode result) {
        if (result.isArray()) {
            result.forEach(row -> orderService.checkVisible(row.get("id").longValue()));
        } else {
            orderService.checkVisible(result.get("id").longValue());
        }
    }
}
