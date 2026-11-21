package com.lingmao.scm.module.inventory.controller;

import com.lingmao.scm.common.excel.Excel;
import com.lingmao.scm.common.page.PageResult;
import com.lingmao.scm.common.page.SeekPage;
import com.lingmao.scm.common.result.R;
import com.lingmao.scm.module.inventory.dto.StockLogQuery;
import com.lingmao.scm.module.inventory.dto.StockLogRowVO;
import com.lingmao.scm.module.inventory.dto.StockMovementQuery;
import com.lingmao.scm.module.inventory.dto.StockMovementRowVO;
import com.lingmao.scm.module.inventory.dto.StockQtyVO;
import com.lingmao.scm.module.inventory.dto.StockQuery;
import com.lingmao.scm.module.inventory.dto.StockRowVO;
import com.lingmao.scm.module.inventory.dto.StockSummaryRowVO;
import com.lingmao.scm.module.inventory.service.StockService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

/** 库存、库存日志（docs/api/04 §3.6）：只读 */
@Tag(name = "库存")
@RestController
@RequiredArgsConstructor
public class StockController {

    private final StockService stockService;
    private final Clock clock;
    private final com.lingmao.scm.module.inventory.service.StockLogExportService logExporter;

    @Operation(summary = "库存明细：仓库 × 物料 SKU（物料仓、工厂仓）")
    @GetMapping("/api/stocks")
    @PreAuthorize("hasAuthority('inventory:stock:list')")
    public R<PageResult<StockRowVO>> page(@Valid StockQuery query) {
        return R.ok(stockService.page(query));
    }

    @Operation(summary = "按物料汇总：物料仓合计、工厂仓合计、合计")
    @GetMapping("/api/stocks/summary")
    @PreAuthorize("hasAuthority('inventory:stock:list')")
    public R<PageResult<StockSummaryRowVO>> summary(@Valid StockQuery query) {
        return R.ok(stockService.summary(query));
    }

    @Operation(summary = "一个仓几个 SKU 的现库存（建单时的出仓库存），没有库存记录的是 0")
    @GetMapping("/api/stocks/qty")
    @PreAuthorize("hasAnyAuthority('inventory:stock:list', 'inventory:doc:add')")
    public R<List<StockQtyVO>> qty(@RequestParam("warehouseId") long warehouseId,
                                   @RequestParam("skuIds") @Size(max = 500, message = "一次最多查 500 种") List<Long> skuIds) {
        return R.ok(stockService.qty(warehouseId, skuIds));
    }

    @Operation(summary = "导出库存明细")
    @GetMapping("/api/stocks/export")
    @PreAuthorize("hasAuthority('inventory:stock:export')")
    public ResponseEntity<byte[]> export(@Valid StockQuery query) {
        return Excel.download(Excel.exportFileName("库存", LocalDate.now(clock)), stockService.export(query));
    }

    @Operation(summary = "导出按物料汇总")
    @GetMapping("/api/stocks/summary/export")
    @PreAuthorize("hasAuthority('inventory:stock:export')")
    public ResponseEntity<byte[]> exportSummary(@Valid StockQuery query) {
        return Excel.download(Excel.exportFileName("库存汇总", LocalDate.now(clock)), stockService.exportSummary(query));
    }

    @Operation(summary = "进销存汇总：期间里每个物料 SKU 的期初、入库、出库、期末（从库存日志现算）")
    @GetMapping("/api/stocks/movement")
    @PreAuthorize("hasAuthority('inventory:stock:list')")
    public R<PageResult<StockMovementRowVO>> movement(@Valid StockMovementQuery query) {
        return R.ok(stockService.movement(query));
    }

    @Operation(summary = "导出进销存汇总")
    @GetMapping("/api/stocks/movement/export")
    @PreAuthorize("hasAuthority('inventory:stock:export')")
    public ResponseEntity<byte[]> exportMovement(@Valid StockMovementQuery query) {
        return Excel.download(Excel.exportFileName("进销存", LocalDate.now(clock)), stockService.exportMovement(query));
    }

    @Operation(summary = "库存日志，新的在前")
    @GetMapping("/api/stock-logs")
    @PreAuthorize("hasAuthority('inventory:stock:list')")
    public R<PageResult<StockLogRowVO>> logs(@Valid StockLogQuery query) {
        return R.ok(stockService.logs(query));
    }

    @Operation(summary = "库存流水游标页：按记录时间、ID倒序，不附带全表计数")
    @GetMapping("/api/stock-logs/seek")
    @PreAuthorize("hasAuthority('inventory:stock:list')")
    public R<SeekPage<StockLogRowVO>> seekLogs(@Valid StockLogQuery query) {
        return R.ok(stockService.seekLogs(query));
    }

    @Operation(summary = "导出库存日志")
    @GetMapping("/api/stock-logs/export")
    @PreAuthorize("hasAuthority('inventory:stock:export')")
    public void exportLogs(@Valid StockLogQuery query, jakarta.servlet.http.HttpServletResponse response) throws java.io.IOException {
        var plan = logExporter.prepareSync(query);
        response.setContentType(Excel.CONTENT_TYPE);
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Content-Disposition", org.springframework.http.ContentDisposition.attachment()
                .filename(Excel.exportFileName("库存日志", LocalDate.now(clock)), java.nio.charset.StandardCharsets.UTF_8).build().toString());
        logExporter.write(plan, response.getOutputStream(), rows -> {});
    }
}
