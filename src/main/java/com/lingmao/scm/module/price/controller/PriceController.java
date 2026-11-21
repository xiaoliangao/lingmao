package com.lingmao.scm.module.price.controller;

import com.lingmao.scm.common.log.OperLog;
import com.lingmao.scm.common.page.PageResult;
import com.lingmao.scm.common.result.R;
import com.lingmao.scm.module.price.dto.CurrentPriceVO;
import com.lingmao.scm.module.price.dto.PriceQuery;
import com.lingmao.scm.module.price.dto.PriceRowVO;
import com.lingmao.scm.module.price.dto.PriceSaveReq;
import com.lingmao.scm.module.price.dto.PriceVoidReq;
import com.lingmao.scm.module.price.entity.PriceType;
import com.lingmao.scm.module.price.service.PriceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * 价格档案（RFC 006 §7）。没有单独的页面，物料、供应商、款式详情的"价格记录"页签调这里。
 * 录、作废按类型要 price:material:edit 或 price:process:edit：方法上先挡住两个都没有的，Service 再按这条的类型查具体的那个（403）
 */
@Tag(name = "价格档案")
@RestController
@RequestMapping("/api/prices")
@RequiredArgsConstructor
public class PriceController {

    private static final String EDIT_ANY = "hasAnyAuthority('price:material:edit', 'price:process:edit')";

    private final PriceService priceService;

    @Operation(summary = "分页：全部历史，或者只看现价（current=true，每组一行）")
    @GetMapping
    @PreAuthorize("hasAuthority('price:price:list')")
    public R<PageResult<PriceRowVO>> page(@Valid PriceQuery query) {
        return R.ok(priceService.page(query));
    }

    @Operation(summary = "录价对话框用：这一组那一天的现价、单位、供应商的默认税率")
    @GetMapping("/current")
    @PreAuthorize(EDIT_ANY)
    public R<CurrentPriceVO> current(@Parameter(description = "MATERIAL 物料价 / PROCESS 加工价") @RequestParam("type") PriceType type,
                                     @RequestParam("supplierId") long supplierId,
                                     @RequestParam(value = "materialId", required = false) Long materialId,
                                     @RequestParam(value = "materialSkuId", required = false) Long materialSkuId,
                                     @RequestParam(value = "styleId", required = false) Long styleId,
                                     @Parameter(description = "加工方式（加工价才用）") @RequestParam(value = "processMode", required = false) String processMode,
                                     @Parameter(description = "取哪天有效的价，不传是今天") @RequestParam(value = "date", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return R.ok(priceService.currentFor(type, supplierId, materialId, materialSkuId, styleId, processMode, date));
    }

    @Operation(summary = "录一条（物料价要 price:material:edit，加工价要 price:process:edit）")
    @PostMapping
    @PreAuthorize(EDIT_ANY)
    @OperLog(module = "价格档案", action = "录价格")
    public R<Long> record(@Valid @RequestBody PriceSaveReq req) {
        return R.ok(priceService.record(req));
    }

    @Operation(summary = "作废（原因必填；按那条的类型要改价权限）")
    @PostMapping("/{id:\\d+}/void")
    @PreAuthorize(EDIT_ANY)
    @OperLog(module = "价格档案", action = "作废")
    public R<Void> voidPrice(@PathVariable("id") long id, @Valid @RequestBody PriceVoidReq req) {
        priceService.voidPrice(id, req.getReason().strip());
        return R.ok();
    }
}
