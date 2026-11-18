package com.lingmao.scm.module.order.controller;

import com.lingmao.scm.common.idempotency.IdempotentCreate;
import com.lingmao.scm.common.idempotency.IdempotencyReplayGuard;
import tools.jackson.databind.JsonNode;

import com.lingmao.scm.common.log.OperLog;
import com.lingmao.scm.common.page.PageResult;
import com.lingmao.scm.common.result.R;
import com.lingmao.scm.module.biz.dto.AttachmentReq;
import com.lingmao.scm.module.biz.dto.AttachmentVO;
import com.lingmao.scm.module.biz.dto.DocActionReq;
import com.lingmao.scm.module.biz.dto.DocLogVO;
import com.lingmao.scm.module.biz.dto.DocSaved;
import com.lingmao.scm.module.biz.entity.BizType;
import com.lingmao.scm.module.biz.service.DocCommonService;
import com.lingmao.scm.module.order.dto.OrderChangeCreateReq;
import com.lingmao.scm.module.order.dto.OrderChangeDetailVO;
import com.lingmao.scm.module.order.dto.OrderChangeQuery;
import com.lingmao.scm.module.order.dto.OrderChangeRowVO;
import com.lingmao.scm.module.order.dto.OrderChangeSaveReq;
import com.lingmao.scm.module.order.service.OrderChangeService;
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
import java.util.Map;

/** 订单变更单（docs/api/02 §3.14）。权限码 order:change:*，单级审核；撤回只给制单人（Service 里查） */
@Tag(name = "订单变更单")
@RestController
@RequestMapping("/api/order-changes")
@RequiredArgsConstructor
public class OrderChangeController extends IdempotencyReplayGuard {

    private final OrderChangeService changeService;
    private final DocCommonService docCommonService;

    @Operation(summary = "列表：制单时间倒序；订单详情的变更页签带 orderId。已驳回 = status=DRAFT&rejected=true")
    @GetMapping
    @PreAuthorize("hasAuthority('order:change:list')")
    public R<PageResult<OrderChangeRowVO>> page(@Valid OrderChangeQuery query) {
        return R.ok(changeService.page(query));
    }

    @Operation(summary = "各状态几张：DRAFT 是全部草稿，REJECTED 是其中被驳回的")
    @GetMapping("/counts")
    @PreAuthorize("hasAuthority('order:change:list')")
    public R<Map<String, Long>> counts(@Valid OrderChangeQuery query) {
        return R.ok(changeService.counts(query));
    }

    @Operation(summary = "详情：每一处改动和说明")
    @GetMapping("/{id:\\d+}")
    @PreAuthorize("hasAuthority('order:change:list')")
    public R<OrderChangeDetailVO> detail(@PathVariable("id") long id) {
        return R.ok(changeService.detail(id));
    }

    @Operation(summary = "新建草稿：发改完以后的样子，后端比出改了哪几处；检查和提交一样（1000）")
    @PostMapping
    @PreAuthorize("hasAuthority('order:change:add')")
    @OperLog(module = "订单变更单", action = "新建")
    @IdempotentCreate("ORDER_CHANGE_CREATE")
    public R<DocSaved> create(@Valid @RequestBody OrderChangeCreateReq req) {
        return R.ok(changeService.create(req));
    }

    @Operation(summary = "改草稿（被驳回的改了再提交），返回新的 version")
    @PutMapping("/{id:\\d+}")
    @PreAuthorize("hasAuthority('order:change:edit')")
    @OperLog(module = "订单变更单", action = "保存")
    public R<Integer> update(@PathVariable("id") long id, @Valid @RequestBody OrderChangeSaveReq req) {
        return R.ok(changeService.update(id, req));
    }

    @Operation(summary = "删草稿")
    @DeleteMapping("/{id:\\d+}")
    @PreAuthorize("hasAuthority('order:change:remove')")
    @OperLog(module = "订单变更单", action = "删除")
    public R<Void> delete(@PathVariable("id") long id) {
        changeService.delete(id);
        return R.ok();
    }

    @Operation(summary = "提交：再查一遍；取消订单时有下游单据 1005")
    @PostMapping("/{id:\\d+}/submit")
    @PreAuthorize("hasAuthority('order:change:submit')")
    @OperLog(module = "订单变更单", action = "提交")
    public R<Void> submit(@PathVariable("id") long id, @Valid @RequestBody DocActionReq req) {
        changeService.act(id, "submit", req);
        return R.ok();
    }

    @Operation(summary = "撤回（制单人）")
    @PostMapping("/{id:\\d+}/withdraw")
    @OperLog(module = "订单变更单", action = "撤回")
    public R<Void> withdraw(@PathVariable("id") long id, @Valid @RequestBody DocActionReq req) {
        changeService.act(id, "withdraw", req);
        return R.ok();
    }

    @Operation(summary = "驳回，要写原因；回到草稿，列表显示已驳回")
    @PostMapping("/{id:\\d+}/reject")
    @PreAuthorize("hasAuthority('order:change:audit')")
    @OperLog(module = "订单变更单", action = "驳回")
    public R<Void> reject(@PathVariable("id") long id, @Valid @RequestBody DocActionReq req) {
        changeService.act(id, "reject", req);
        return R.ok();
    }

    @Operation(summary = "审核：写回订单（这期间订单变了 1002），给下游单据发变更提醒")
    @PostMapping("/{id:\\d+}/audit")
    @PreAuthorize("hasAuthority('order:change:audit')")
    @OperLog(module = "订单变更单", action = "审核")
    public R<Void> audit(@PathVariable("id") long id, @Valid @RequestBody DocActionReq req) {
        changeService.act(id, "audit", req);
        return R.ok();
    }

    @Operation(summary = "作废（草稿、待审核）")
    @PostMapping("/{id:\\d+}/void")
    @PreAuthorize("hasAuthority('order:change:void')")
    @OperLog(module = "订单变更单", action = "作废")
    public R<Void> voidDoc(@PathVariable("id") long id, @Valid @RequestBody DocActionReq req) {
        changeService.act(id, "void", req);
        return R.ok();
    }

    // ---------- 单据公共接口（README §4.3） ----------

    @Operation(summary = "操作记录，新的在前")
    @GetMapping("/{id:\\d+}/logs")
    @PreAuthorize("hasAuthority('order:change:list')")
    public R<List<DocLogVO>> logs(@PathVariable("id") long id) {
        return R.ok(docCommonService.logs(BizType.ORDER_CHANGE, id, changeService::checkVisible));
    }

    @Operation(summary = "变更依据附件")
    @GetMapping("/{id:\\d+}/attachments")
    @PreAuthorize("hasAuthority('order:change:list')")
    public R<List<AttachmentVO>> attachments(@PathVariable("id") long id) {
        return R.ok(docCommonService.attachments(BizType.ORDER_CHANGE, id, changeService::checkVisible));
    }

    @Operation(summary = "附件整组替换")
    @PutMapping("/{id:\\d+}/attachments")
    @PreAuthorize("hasAuthority('order:change:edit')")
    @OperLog(module = "订单变更单", action = "换附件")
    public R<List<AttachmentVO>> replaceAttachments(@PathVariable("id") long id, @RequestBody List<@Valid AttachmentReq> files) {
        return R.ok(docCommonService.replaceAttachments(BizType.ORDER_CHANGE, id, files, changeService::checkVisible));
    }

    /** 重放只读检查当前对象范围，不能因为曾经成功就绕过之后的降权。 */
    @Override
    public void authorizeReplay(String operation, Object[] arguments, JsonNode result) {
        changeService.checkSourceOrderVisible(((OrderChangeCreateReq) arguments[0]).getOrderId());
        if (result.isArray()) {
            result.forEach(row -> changeService.checkVisible(row.get("id").longValue()));
        } else {
            changeService.checkVisible(result.get("id").longValue());
        }
    }
}
