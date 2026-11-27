package com.lingmao.scm.module.production.controller;

import com.lingmao.scm.common.excel.Excel;
import com.lingmao.scm.common.page.PageResult;
import com.lingmao.scm.common.result.R;
import com.lingmao.scm.module.production.dto.LedgerMaterialQuery;
import com.lingmao.scm.module.production.dto.LedgerMaterialRowVO;
import com.lingmao.scm.module.production.dto.LedgerOrderQuery;
import com.lingmao.scm.module.production.dto.LedgerOrderRowVO;
import com.lingmao.scm.module.production.service.FactoryLedgerService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Map;

/** 工厂料账（RFC 015 §5）：按生产单看领了多少、该用掉多少、核销了没有；按工厂物料看工厂手上估计还有多少 */
@Tag(name = "工厂料账")
@RestController
@RequestMapping("/api/factory-ledger")
@RequiredArgsConstructor
public class FactoryLedgerController {

    private final FactoryLedgerService factoryLedgerService;
    private final Clock clock;

    @Operation(summary = "按生产单：进料账的生产单，带料账状态、良品、超耗和该退的料几种")
    @GetMapping("/orders")
    @PreAuthorize("hasAuthority('production:ledger:list')")
    public R<PageResult<LedgerOrderRowVO>> orders(@Valid LedgerOrderQuery query) {
        return R.ok(factoryLedgerService.orders(query));
    }

    @Operation(summary = "按生产单：每种料账状态各几张（IN_PROGRESS / TO_SETTLE / SETTLING / SETTLED）")
    @GetMapping("/orders/counts")
    @PreAuthorize("hasAuthority('production:ledger:list')")
    public R<Map<String, Long>> counts(@Valid LedgerOrderQuery query) {
        return R.ok(factoryLedgerService.counts(query));
    }

    @Operation(summary = "按工厂物料：每个工厂仓 × 物料的账面、未核销的耗料、估计手上")
    @GetMapping("/materials")
    @PreAuthorize("hasAuthority('production:ledger:list')")
    public R<PageResult<LedgerMaterialRowVO>> materials(@Valid LedgerMaterialQuery query) {
        return R.ok(factoryLedgerService.materials(query));
    }

    @Operation(summary = "导出按工厂物料（发给工厂对账）")
    @GetMapping("/materials/export")
    @PreAuthorize("hasAuthority('production:ledger:export')")
    public ResponseEntity<byte[]> exportMaterials(@Valid LedgerMaterialQuery query) {
        return Excel.download(Excel.exportFileName("工厂料账", LocalDate.now(clock)), factoryLedgerService.exportMaterials(query));
    }
}
