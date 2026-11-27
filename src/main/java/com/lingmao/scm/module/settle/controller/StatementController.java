package com.lingmao.scm.module.settle.controller;

import com.lingmao.scm.common.excel.Excel;
import com.lingmao.scm.common.log.OperLog;
import com.lingmao.scm.common.page.PageResult;
import com.lingmao.scm.common.result.R;
import com.lingmao.scm.module.biz.dto.AttachmentVO;
import com.lingmao.scm.module.biz.dto.DocActionReq;
import com.lingmao.scm.module.biz.dto.DocLogVO;
import com.lingmao.scm.module.biz.entity.BizType;
import com.lingmao.scm.module.biz.service.DocCommonService;
import com.lingmao.scm.module.settle.dto.CompareNotesReq;
import com.lingmao.scm.module.settle.dto.CompareResult;
import com.lingmao.scm.module.settle.dto.PendingStatementQuery;
import com.lingmao.scm.module.settle.dto.PendingStatementRow;
import com.lingmao.scm.module.settle.dto.StatementCreateReq;
import com.lingmao.scm.module.settle.dto.StatementCreatedVO;
import com.lingmao.scm.module.settle.dto.StatementDeductionReq;
import com.lingmao.scm.module.settle.dto.StatementDetailVO;
import com.lingmao.scm.module.settle.dto.StatementItemsReq;
import com.lingmao.scm.module.settle.dto.StatementQuery;
import com.lingmao.scm.module.settle.dto.StatementRowVO;
import com.lingmao.scm.module.settle.service.StatementService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/** 供应商对账单（甲方 11-27 答 Q68，RFC 019 §5，docs/api/09 §2） */
@Tag(name = "供应商对账")
@RestController
@RequestMapping("/api/statements")
@RequiredArgsConstructor
public class StatementController {

    private final StatementService statementService;
    private final DocCommonService docCommonService;

    @Operation(summary = "对账单列表，新的在前")
    @GetMapping
    @PreAuthorize("hasAuthority('settle:statement:list')")
    public R<PageResult<StatementRowVO>> page(@Valid StatementQuery query) {
        return R.ok(statementService.page(query));
    }

    @Operation(summary = "待对账：按主体 × 供应商 × 月份列出还没进对账单的单据数和实付金额，新的月份在前（最多 500 组）")
    @GetMapping("/pending")
    @PreAuthorize("hasAuthority('settle:statement:list')")
    public R<List<PendingStatementRow>> pending(@Valid PendingStatementQuery query) {
        return R.ok(statementService.pending(query));
    }

    @Operation(summary = "详情：明细、合计、比对结果、能做的动作")
    @GetMapping("/{id:\\d+}")
    @PreAuthorize("hasAuthority('settle:statement:list')")
    public R<StatementDetailVO> detail(@PathVariable("id") long id) {
        return R.ok(statementService.detail(id));
    }

    @Operation(summary = "生成对账单：把这段时间里这家还没对过的采购入库、采购退货、回货、扣款拉进来；一张都没有报 1000")
    @PostMapping
    @PreAuthorize("hasAuthority('settle:statement:edit')")
    @OperLog(module = "供应商对账", action = "生成")
    public R<StatementCreatedVO> create(@Valid @RequestBody StatementCreateReq req) {
        return R.ok(statementService.create(req));
    }

    @Operation(summary = "草稿刷新：这段时间里后来审核的单据加进来；added 是加进来几行")
    @PostMapping("/{id:\\d+}/refresh")
    @PreAuthorize("hasAuthority('settle:statement:edit')")
    @OperLog(module = "供应商对账", action = "刷新")
    public R<StatementCreatedVO> refresh(@PathVariable("id") long id, @Valid @RequestBody DocActionReq req) {
        return R.ok(statementService.refresh(id, req.version()));
    }

    @Operation(summary = "草稿移出几行：单据回到待对账；返回新的 version")
    @PostMapping("/{id:\\d+}/remove-items")
    @PreAuthorize("hasAuthority('settle:statement:edit')")
    @OperLog(module = "供应商对账", action = "移出")
    public R<Integer> removeItems(@PathVariable("id") long id, @Valid @RequestBody StatementItemsReq req) {
        return R.ok(statementService.removeItems(id, req));
    }

    @Operation(summary = "草稿加一笔扣款：同时登记到扣款登记；返回新的 version")
    @PostMapping("/{id:\\d+}/deductions")
    @PreAuthorize("hasAuthority('settle:statement:edit')")
    @OperLog(module = "供应商对账", action = "加扣款")
    public R<Integer> addDeduction(@PathVariable("id") long id, @Valid @RequestBody StatementDeductionReq req) {
        return R.ok(statementService.addDeduction(id, req));
    }

    @Operation(summary = "发送（草稿 → 已发送）：发给对方核对，明细不能再改；空的报 1000")
    @PostMapping("/{id:\\d+}/send")
    @PreAuthorize("hasAuthority('settle:statement:edit')")
    @OperLog(module = "供应商对账", action = "发送")
    public R<Void> send(@PathVariable("id") long id, @Valid @RequestBody DocActionReq req) {
        statementService.act(id, "send", req);
        return R.ok();
    }

    @Operation(summary = "撤回（已发送 → 草稿）：对方有异议，改了再发")
    @PostMapping("/{id:\\d+}/withdraw")
    @PreAuthorize("hasAuthority('settle:statement:edit')")
    @OperLog(module = "供应商对账", action = "撤回")
    public R<Void> withdraw(@PathVariable("id") long id, @Valid @RequestBody DocActionReq req) {
        statementService.act(id, "withdraw", req);
        return R.ok();
    }

    @Operation(summary = "确认（已发送 → 已确认）：双方对上了；reason 是确认时写的一句。比对过的，差异都要写了说明（不然 1000 + 清单）")
    @PostMapping("/{id:\\d+}/confirm")
    @PreAuthorize("hasAuthority('settle:statement:confirm')")
    @OperLog(module = "供应商对账", action = "确认")
    public R<Void> confirm(@PathVariable("id") long id, @Valid @RequestBody DocActionReq req) {
        statementService.act(id, "confirm", req);
        return R.ok();
    }

    @Operation(summary = "作废（只有草稿能作废）：里面的单据、扣款回到待对账")
    @PostMapping("/{id:\\d+}/void")
    @PreAuthorize("hasAuthority('settle:statement:edit')")
    @OperLog(module = "供应商对账", action = "作废")
    public R<Void> voidStatement(@PathVariable("id") long id, @Valid @RequestBody DocActionReq req) {
        statementService.act(id, "void", req);
        return R.ok();
    }

    @Operation(summary = "上传对方的 Excel 对账单比对（草稿、已发送）：结果存在对账单上，原件存附件；认不出表头报 1000")
    @PostMapping(value = "/{id:\\d+}/compare", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('settle:statement:edit')")
    @OperLog(module = "供应商对账", action = "比对")
    public R<CompareResult> compare(@PathVariable("id") long id, @RequestParam("version") int version,
                                    @RequestParam("file") MultipartFile file) {
        return R.ok(statementService.compare(id, version, file));
    }

    @Operation(summary = "写差异说明（和对方谈的结果）：只改传了的几处；返回新的 version")
    @PutMapping("/{id:\\d+}/compare/notes")
    @PreAuthorize("hasAuthority('settle:statement:edit')")
    @OperLog(module = "供应商对账", action = "写差异说明")
    public R<Integer> saveNotes(@PathVariable("id") long id, @Valid @RequestBody CompareNotesReq req) {
        return R.ok(statementService.saveNotes(id, req));
    }

    @Operation(summary = "对方对账单的原件（最近一次比对传的）")
    @GetMapping("/{id:\\d+}/their-files")
    @PreAuthorize("hasAuthority('settle:statement:list')")
    public R<List<AttachmentVO>> theirFiles(@PathVariable("id") long id) {
        return R.ok(statementService.theirFiles(id));
    }

    @Operation(summary = "导出成 Excel 发给对方核对（最后两行是合计、实付）；导出的文件原样传回来也能比对")
    @GetMapping("/{id:\\d+}/export")
    @PreAuthorize("hasAuthority('settle:statement:list')")
    @OperLog(module = "供应商对账", action = "导出")
    public ResponseEntity<byte[]> export(@PathVariable("id") long id) {
        return Excel.download(statementService.exportName(id), statementService.export(id));
    }

    @Operation(summary = "操作记录，新的在前")
    @GetMapping("/{id:\\d+}/logs")
    @PreAuthorize("hasAuthority('settle:statement:list')")
    public R<List<DocLogVO>> logs(@PathVariable("id") long id) {
        return R.ok(docCommonService.logs(BizType.STATEMENT, id, statementService::checkVisible));
    }
}
