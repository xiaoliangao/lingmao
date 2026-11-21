package com.lingmao.scm.module.production.controller;

import com.lingmao.scm.common.page.PageResult;
import com.lingmao.scm.common.result.R;
import com.lingmao.scm.module.production.dto.OrderProgressCounts;
import com.lingmao.scm.module.production.dto.OrderProgressQuery;
import com.lingmao.scm.module.production.dto.OrderProgressRowVO;
import com.lingmao.scm.module.production.service.OrderProgressService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 订单进度总览（RFC 016 §1，docs/api/02 §3.15）：只看在手的需求订单，照需求订单的数据范围和品类线 */
@Tag(name = "订单进度总览")
@RestController
@RequestMapping("/api/order-progress")
@RequiredArgsConstructor
public class OrderProgressController {

    private final OrderProgressService progressService;

    @Operation(summary = "列表：一行是一张订单里的一个款；按风险、交期排")
    @GetMapping
    @PreAuthorize("hasAuthority('order:progress:list')")
    public R<PageResult<OrderProgressRowVO>> page(@Valid OrderProgressQuery query) {
        return R.ok(progressService.page(query));
    }

    @Operation(summary = "分段上的数：全部、未排完、临期、已逾期、生产有风险")
    @GetMapping("/counts")
    @PreAuthorize("hasAuthority('order:progress:list')")
    public R<OrderProgressCounts> counts(@Valid OrderProgressQuery query) {
        return R.ok(progressService.counts(query));
    }
}
