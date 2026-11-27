package com.lingmao.scm.module.quality.controller;

import com.lingmao.scm.common.log.OperLog;
import com.lingmao.scm.common.page.PageResult;
import com.lingmao.scm.common.result.R;
import com.lingmao.scm.module.quality.dto.QcCreatedVO;
import com.lingmao.scm.module.quality.dto.QcOrderOption;
import com.lingmao.scm.module.quality.dto.QcPendingQuery;
import com.lingmao.scm.module.quality.dto.QcPendingRow;
import com.lingmao.scm.module.quality.dto.QcQuery;
import com.lingmao.scm.module.quality.dto.QcReq;
import com.lingmao.scm.module.quality.dto.QcReworkReq;
import com.lingmao.scm.module.quality.dto.QcRowVO;
import com.lingmao.scm.module.quality.dto.QcSourceVO;
import com.lingmao.scm.module.quality.dto.QcStatQuery;
import com.lingmao.scm.module.quality.dto.QcSupplierStat;
import com.lingmao.scm.module.quality.entity.QcKind;
import com.lingmao.scm.module.quality.service.QcInspectionService;
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

/** 质检（RFC 019 §6，docs/api/10）：来料检验、产前样、中检、尾期验货，回货不合格返修，质量扣款 */
@Tag(name = "质检")
@RestController
@RequestMapping("/api/qc-inspections")
@RequiredArgsConstructor
public class QcInspectionController {

    private final QcInspectionService qcService;

    @Operation(summary = "质检记录列表，检验日期新的在前")
    @GetMapping
    @PreAuthorize("hasAuthority('quality:inspection:list')")
    public R<PageResult<QcRowVO>> page(@Valid QcQuery query) {
        return R.ok(qcService.page(query));
    }

    @Operation(summary = "待检：最近 30 天已审核还没验的采购入库单、回货单，加上返修完成等再验的回货单")
    @GetMapping("/pending")
    @PreAuthorize("hasAuthority('quality:inspection:list')")
    public R<PageResult<QcPendingRow>> pending(@Valid QcPendingQuery query) {
        return R.ok(qcService.pending(query));
    }

    @Operation(summary = "不合格、返修：尾期验货不合格还没返修完的回货（不合格待安排返修、返修中）")
    @GetMapping("/reworks")
    @PreAuthorize("hasAuthority('quality:inspection:list')")
    public R<List<QcRowVO>> reworks(@RequestParam(name = "companyId", required = false) Long companyId) {
        return R.ok(qcService.reworks(companyId));
    }

    @Operation(summary = "供应商质量：按家统计检验次数、结论、常见问题、质量扣款（默认最近 90 天）")
    @GetMapping("/supplier-stats")
    @PreAuthorize("hasAuthority('quality:inspection:list')")
    public R<List<QcSupplierStat>> supplierStats(@Valid QcStatQuery query) {
        return R.ok(qcService.supplierStats(query));
    }

    @Operation(summary = "记质检前看来源单：批量、AQL 建议抽检数和允收数、前几次的检验；现在不能验的回 1000")
    @GetMapping("/source")
    @PreAuthorize("hasAuthority('quality:inspection:edit')")
    public R<QcSourceVO> source(@RequestParam("kind") QcKind kind, @RequestParam("sourceId") long sourceId) {
        return R.ok(qcService.source(kind, sourceId));
    }

    @Operation(summary = "记产前样、中检时选生产单：已审核、生产中、已完成的，按单号、款号款名、加工厂搜，最多 20 张")
    @GetMapping("/order-options")
    @PreAuthorize("hasAuthority('quality:inspection:edit')")
    public R<List<QcOrderOption>> orderOptions(@RequestParam(name = "keyword", required = false) String keyword) {
        return R.ok(qcService.orderOptions(keyword));
    }

    @Operation(summary = "一条质检记录，带照片")
    @GetMapping("/{id:\\d+}")
    @PreAuthorize("hasAuthority('quality:inspection:list')")
    public R<QcRowVO> detail(@PathVariable("id") long id) {
        return R.ok(qcService.detail(id));
    }

    @Operation(summary = "记一条质检；填了扣款金额同时登记一笔扣款（要扣款登记权限）")
    @PostMapping
    @PreAuthorize("hasAuthority('quality:inspection:edit')")
    @OperLog(module = "质检", action = "验货")
    public R<QcCreatedVO> create(@Valid @RequestBody QcReq req) {
        return R.ok(qcService.create(req));
    }

    @Operation(summary = "改（要带 version），扣款跟着改；安排了返修的结论不能改；返回新的 version")
    @PutMapping("/{id:\\d+}")
    @PreAuthorize("hasAuthority('quality:inspection:edit')")
    @OperLog(module = "质检", action = "改")
    public R<Integer> update(@PathVariable("id") long id, @Valid @RequestBody QcReq req) {
        return R.ok(qcService.update(id, req));
    }

    @Operation(summary = "删，扣款一起删（扣款进了对账单的不能删，1000）；安排了返修的不能删")
    @DeleteMapping("/{id:\\d+}")
    @PreAuthorize("hasAuthority('quality:inspection:edit')")
    @OperLog(module = "质检", action = "删除")
    public R<Void> delete(@PathVariable("id") long id) {
        qcService.delete(id);
        return R.ok();
    }

    @Operation(summary = "尾期验货不合格：拉回工厂返修，写返修说明；返回新的 version")
    @PostMapping("/{id:\\d+}/rework")
    @PreAuthorize("hasAuthority('quality:inspection:edit')")
    @OperLog(module = "质检", action = "返修")
    public R<Integer> rework(@PathVariable("id") long id, @Valid @RequestBody QcReworkReq req) {
        return R.ok(qcService.rework(id, req));
    }

    @Operation(summary = "返修完成：这张回货单回到待检，再验一次；返回新的 version")
    @PostMapping("/{id:\\d+}/rework-done")
    @PreAuthorize("hasAuthority('quality:inspection:edit')")
    @OperLog(module = "质检", action = "返修完成")
    public R<Integer> reworkDone(@PathVariable("id") long id, @Valid @RequestBody QcReworkReq req) {
        return R.ok(qcService.reworkDone(id, req));
    }
}
