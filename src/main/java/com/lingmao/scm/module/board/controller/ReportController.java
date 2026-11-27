package com.lingmao.scm.module.board.controller;

import com.lingmao.scm.common.excel.Excel;
import com.lingmao.scm.common.page.PageResult;
import com.lingmao.scm.common.result.R;
import com.lingmao.scm.module.board.dto.FactoryPerfRow;
import com.lingmao.scm.module.board.dto.LineWipRow;
import com.lingmao.scm.module.board.dto.OrderMarginPage;
import com.lingmao.scm.module.board.dto.OrderMarginQuery;
import com.lingmao.scm.module.board.dto.PerfQuery;
import com.lingmao.scm.module.board.dto.PerfResult;
import com.lingmao.scm.module.board.dto.PurchasePerfRow;
import com.lingmao.scm.module.board.dto.StaleDaysReq;
import com.lingmao.scm.module.board.dto.StaleMaterialQuery;
import com.lingmao.scm.module.board.dto.StaleMaterialRow;
import com.lingmao.scm.module.board.service.ReportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

/** 报表（甲方 11-27 答 Q66、Q67、Q53，RFC 019 §3）：供应商绩效、在做件数、呆滞料、订单毛利。都是现算的 */
@Tag(name = "报表")
@RestController
@RequestMapping("/api/reports")
@RequiredArgsConstructor
public class ReportController {

    private final ReportService reportService;
    private final Clock clock;

    @Operation(summary = "物料供应商的采购到货及时率：按采购行，交期在这段时间里的")
    @GetMapping("/supplier-performance/materials")
    @PreAuthorize("hasAuthority('report:supplier:list')")
    public R<PerfResult<PurchasePerfRow>> purchasePerf(@Valid PerfQuery query) {
        return R.ok(reportService.purchasePerf(query));
    }

    @Operation(summary = "加工厂的交期达成率（按生产单）、次品率（按这段时间里的回货）")
    @GetMapping("/supplier-performance/factories")
    @PreAuthorize("hasAuthority('report:supplier:list')")
    public R<PerfResult<FactoryPerfRow>> factoryPerf(@Valid PerfQuery query) {
        return R.ok(reportService.factoryPerf(query));
    }

    @Operation(summary = "各品类线在做的件数（按品类线 × 一级品类），month（yyyy-MM）管这个月新排、这个月回货两列")
    @GetMapping("/line-wip")
    @PreAuthorize("hasAuthority('report:wip:list')")
    public R<List<LineWipRow>> lineWip(@RequestParam(value = "month", required = false) String month) {
        return R.ok(reportService.lineWip(month));
    }

    @Operation(summary = "呆滞料：物料仓里有库存、多少天没出没入的")
    @GetMapping("/stale-materials")
    @PreAuthorize("hasAuthority('report:stale:list')")
    public R<PageResult<StaleMaterialRow>> staleMaterials(@Valid StaleMaterialQuery query) {
        return R.ok(reportService.staleMaterials(query));
    }

    @Operation(summary = "导出呆滞料")
    @GetMapping("/stale-materials/export")
    @PreAuthorize("hasAuthority('report:stale:export')")
    public ResponseEntity<byte[]> exportStaleMaterials(@Valid StaleMaterialQuery query) {
        return Excel.download(Excel.exportFileName("呆滞料", LocalDate.now(clock)), reportService.exportStaleMaterials(query));
    }

    @Operation(summary = "多少天没动算呆滞（默认天数，没设过是 90）")
    @GetMapping("/stale-materials/days")
    @PreAuthorize("hasAuthority('report:stale:list')")
    public R<Integer> staleDays() {
        return R.ok(reportService.staleDays());
    }

    @Operation(summary = "改多少天没动算呆滞（首页、预警中心的数按这个算）")
    @PutMapping("/stale-materials/days")
    @PreAuthorize("hasAuthority('report:stale:edit')")
    public R<Void> setStaleDays(@Valid @RequestBody StaleDaysReq req) {
        reportService.setStaleDays(req.days());
        return R.ok();
    }

    @Operation(summary = "订单毛利：销售额 − 按资料包核价估的成本（要同时有看订单价格、看成本的权限）")
    @GetMapping("/order-margins")
    @PreAuthorize("hasAuthority('report:margin:list') and hasAuthority('order:price:query') and hasAuthority('techpack:cost:query')")
    public R<OrderMarginPage> orderMargins(@Valid OrderMarginQuery query) {
        return R.ok(reportService.orderMargins(query));
    }
}
