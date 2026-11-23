package com.lingmao.scm.module.purchase.controller;

import com.lingmao.scm.common.idempotency.IdempotentCreate;
import com.lingmao.scm.common.idempotency.IdempotencyReplayGuard;
import tools.jackson.databind.JsonNode;

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
import com.lingmao.scm.module.purchase.dto.MrpCalcDetailVO;
import com.lingmao.scm.module.purchase.dto.MrpCalcQuery;
import com.lingmao.scm.module.purchase.dto.MrpCalcRowVO;
import com.lingmao.scm.module.purchase.dto.MrpCreateReq;
import com.lingmao.scm.module.purchase.dto.MrpItemForm;
import com.lingmao.scm.module.purchase.dto.MrpOrderOptionVO;
import com.lingmao.scm.module.purchase.dto.RemarkReq;
import com.lingmao.scm.module.purchase.dto.ReservePctReq;
import com.lingmao.scm.module.purchase.dto.ReservePctVO;
import com.lingmao.scm.module.purchase.service.MrpService;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 算料单（docs/api/03 §3.2）。权限码 purchase:mrp:*；确认、关闭、重新算料不走通用的状态动作（返回值、校验不一样）
 */
@Tag(name = "算料单")
@RestController
@RequestMapping("/api/mrp-calcs")
@RequiredArgsConstructor
public class MrpCalcController extends IdempotencyReplayGuard {
    /** 标签内部命名空间与已验证条件不能从请求绑定。 */
    @org.springframework.web.bind.annotation.InitBinder("mrpCalcQuery")
    void protectTagFilter(org.springframework.web.bind.WebDataBinder binder) {
        binder.setDisallowedFields("tagFilter*");
    }


    private final MrpService mrpService;
    private final DocCommonService docCommonService;

    @Operation(summary = "列表：建单时间倒序；关键字是算料单号、订单号、款号")
    @GetMapping
    @PreAuthorize("hasAuthority('purchase:mrp:list')")
    public R<PageResult<MrpCalcRowVO>> page(@Valid MrpCalcQuery query) {
        return R.ok(mrpService.page(query));
    }

    @Operation(summary = "详情：全部结果行、本次使用的在途、赶不上的在途；重新算料出来的带和上一张的差异")
    @GetMapping("/{id:\\d+}")
    @PreAuthorize("hasAuthority('purchase:mrp:list')")
    public R<MrpCalcDetailVO> detail(@PathVariable("id") long id) {
        return R.ok(mrpService.detail(id));
    }

    @Operation(summary = "能算料的订单：已审核、生产中，带已经在哪些算料单里")
    @GetMapping("/order-options")
    @PreAuthorize("hasAuthority('purchase:mrp:add')")
    public R<PageResult<MrpOrderOptionVO>> orderOptions(@RequestParam(value = "keyword", required = false) String keyword,
                                                        @Valid PageQuery page) {
        return R.ok(mrpService.orderOptions(keyword, page));
    }

    @Operation(summary = "选订单算料，建草稿；BOM 没配全 1000")
    @PostMapping
    @PreAuthorize("hasAuthority('purchase:mrp:add')")
    @OperLog(module = "算料单", action = "算料")
    @IdempotentCreate("MRP_CREATE")
    public R<DocSaved> create(@Valid @RequestBody MrpCreateReq req) {
        return R.ok(mrpService.create(req));
    }

    @Operation(summary = "改一行（草稿：供应商、本次使用库存 / 在途、建议采购；已确认：只有供应商），返回整张详情")
    @PutMapping("/{id:\\d+}/items/{itemId:\\d+}")
    @PreAuthorize("hasAuthority('purchase:mrp:edit')")
    @OperLog(module = "算料单", action = "改行")
    public R<MrpCalcDetailVO> updateItem(@PathVariable("id") long id, @PathVariable("itemId") long itemId,
                                         @Valid @RequestBody MrpItemForm form) {
        return R.ok(mrpService.updateItem(id, itemId, form));
    }

    @Operation(summary = "删一行（草稿），返回整张详情")
    @DeleteMapping("/{id:\\d+}/items/{itemId:\\d+}")
    @PreAuthorize("hasAuthority('purchase:mrp:edit')")
    @OperLog(module = "算料单", action = "删行")
    public R<MrpCalcDetailVO> removeItem(@PathVariable("id") long id, @PathVariable("itemId") long itemId,
                                         @RequestParam("version") int version) {
        return R.ok(mrpService.removeItem(id, itemId, version));
    }

    @Operation(summary = "改备注（草稿、已确认）")
    @PutMapping("/{id:\\d+}/remark")
    @PreAuthorize("hasAuthority('purchase:mrp:edit')")
    @OperLog(module = "算料单", action = "改备注")
    public R<Void> updateRemark(@PathVariable("id") long id, @Valid @RequestBody RemarkReq req) {
        mrpService.updateRemark(id, req);
        return R.ok();
    }

    @Operation(summary = "确认：按最新的库存、在途再查一遍，变了 1000（可用量已变化）")
    @PostMapping("/{id:\\d+}/confirm")
    @PreAuthorize("hasAuthority('purchase:mrp:edit')")
    @OperLog(module = "算料单", action = "确认")
    public R<Void> confirm(@PathVariable("id") long id, @Valid @RequestBody DocActionReq req) {
        mrpService.confirm(id, req);
        return R.ok();
    }

    @Operation(summary = "关闭（已确认）：放掉占着的库存、在途")
    @PostMapping("/{id:\\d+}/close")
    @PreAuthorize("hasAuthority('purchase:mrp:edit')")
    @OperLog(module = "算料单", action = "关闭")
    public R<Void> close(@PathVariable("id") long id, @Valid @RequestBody DocActionReq req) {
        mrpService.close(id, req);
        return R.ok();
    }

    @Operation(summary = "重新算料：草稿就地重算；已确认的另建一张草稿，确认新单时关掉这张")
    @PostMapping("/{id:\\d+}/recalc")
    @PreAuthorize("hasAuthority('purchase:mrp:add')")
    @OperLog(module = "算料单", action = "重新算料")
    public R<DocSaved> recalc(@PathVariable("id") long id, @Valid @RequestBody DocActionReq req) {
        return R.ok(mrpService.recalc(id, req));
    }

    @Operation(summary = "删草稿")
    @DeleteMapping("/{id:\\d+}")
    @PreAuthorize("hasAuthority('purchase:mrp:remove')")
    @OperLog(module = "算料单", action = "删除")
    public R<Void> delete(@PathVariable("id") long id) {
        mrpService.delete(id);
        return R.ok();
    }

    @Operation(summary = "物料类型的备料比（字典 material_type 的数值）")
    @GetMapping("/reserve-pcts")
    @PreAuthorize("hasAuthority('purchase:mrp:list')")
    public R<List<ReservePctVO>> reservePcts() {
        return R.ok(mrpService.reservePcts());
    }

    @Operation(summary = "改物料类型的备料比：以后算料按新的，已经算好的不变")
    @PutMapping("/reserve-pcts")
    @PreAuthorize("hasAuthority('purchase:mrp:edit')")
    @OperLog(module = "算料单", action = "改备料比")
    public R<Void> saveReservePcts(@RequestBody List<@Valid ReservePctReq> list) {
        mrpService.saveReservePcts(list);
        return R.ok();
    }

    // ---------- 单据公共接口（README §4.3） ----------

    @Operation(summary = "操作记录，新的在前")
    @GetMapping("/{id:\\d+}/logs")
    @PreAuthorize("hasAuthority('purchase:mrp:list')")
    public R<List<DocLogVO>> logs(@PathVariable("id") long id) {
        return R.ok(docCommonService.logs(BizType.MRP_CALC, id, mrpService::checkVisible));
    }

    @Operation(summary = "附件")
    @GetMapping("/{id:\\d+}/attachments")
    @PreAuthorize("hasAuthority('purchase:mrp:list')")
    public R<List<AttachmentVO>> attachments(@PathVariable("id") long id) {
        return R.ok(docCommonService.attachments(BizType.MRP_CALC, id, mrpService::checkVisible));
    }

    @Operation(summary = "附件整组替换（任何状态都能改）")
    @PutMapping("/{id:\\d+}/attachments")
    @PreAuthorize("hasAuthority('purchase:mrp:edit')")
    @OperLog(module = "算料单", action = "换附件")
    public R<List<AttachmentVO>> replaceAttachments(@PathVariable("id") long id, @RequestBody List<@Valid AttachmentReq> files) {
        return R.ok(docCommonService.replaceAttachments(BizType.MRP_CALC, id, files, mrpService::checkVisible));
    }

    @Operation(summary = "下游单据：它生成的采购单")
    @GetMapping("/{id:\\d+}/references")
    @PreAuthorize("hasAuthority('purchase:mrp:list')")
    public R<List<DocRef>> references(@PathVariable("id") long id) {
        return R.ok(docCommonService.references(BizType.MRP_CALC, id, mrpService::checkVisible));
    }

    @Operation(summary = "上游变更提醒：订单变更审过以后插的")
    @GetMapping("/{id:\\d+}/notices")
    @PreAuthorize("hasAuthority('purchase:mrp:list')")
    public R<List<DocNoticeVO>> notices(@PathVariable("id") long id) {
        return R.ok(docCommonService.notices(BizType.MRP_CALC, id, mrpService::checkVisible));
    }

    @Operation(summary = "处理一条上游变更提醒")
    @PostMapping("/{id:\\d+}/notices/{noticeId:\\d+}/handle")
    @PreAuthorize("hasAuthority('purchase:mrp:edit')")
    public R<Void> handleNotice(@PathVariable("id") long id, @PathVariable("noticeId") long noticeId,
                                @Valid @RequestBody NoticeHandleReq req) {
        docCommonService.handleNotice(BizType.MRP_CALC, id, noticeId, req.getRemark(), mrpService::checkVisible);
        return R.ok();
    }

    /** 重放只读检查当前对象范围，不能因为曾经成功就绕过之后的降权。 */
    @Override
    public void authorizeReplay(String operation, Object[] arguments, JsonNode result) {
        mrpService.checkSourceOrdersVisible(((MrpCreateReq) arguments[0]).getOrderIds());
        if (result.isArray()) {
            result.forEach(row -> mrpService.checkVisible(row.get("id").longValue()));
        } else {
            mrpService.checkVisible(result.get("id").longValue());
        }
    }
}
