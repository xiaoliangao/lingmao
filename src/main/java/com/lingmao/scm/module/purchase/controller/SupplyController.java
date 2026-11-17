package com.lingmao.scm.module.purchase.controller;

import com.lingmao.scm.common.result.R;
import com.lingmao.scm.module.purchase.dto.OrderKittingVO;
import com.lingmao.scm.module.purchase.dto.ReorderRowVO;
import com.lingmao.scm.module.purchase.dto.SkuSupplyVO;
import com.lingmao.scm.module.purchase.service.MrpService;
import com.lingmao.scm.module.purchase.service.SupplyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 别的页面要的物料供应信息（docs/api/03 §3.5、§3.6）：齐料状态、补货清单、在途 */
@Tag(name = "物料供应")
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class SupplyController {

    private final MrpService mrpService;
    private final SupplyService supplyService;

    @Operation(summary = "几张订单的齐料状态（最多 500 张）：只回状态不回明细，登录即可")
    @GetMapping("/order-kitting")
    public R<List<OrderKittingVO>> kitting(@RequestParam("orderIds") List<Long> orderIds) {
        return R.ok(mrpService.kitting(orderIds));
    }

    @Operation(summary = "补货清单：低于安全库存的物料，差得多的在前，不分页")
    @GetMapping("/reorder-items")
    @PreAuthorize("hasAuthority('purchase:order:list')")
    public R<List<ReorderRowVO>> reorderItems() {
        return R.ok(supplyService.reorderItems());
    }

    @Operation(summary = "几个 SKU 的在途（按交货仓库）、已被占用、是否低于安全库存（库存页用）")
    @GetMapping("/material-supply")
    @PreAuthorize("hasAnyAuthority('inventory:stock:list', 'purchase:order:list')")
    public R<List<SkuSupplyVO>> materialSupply(@RequestParam("skuIds") List<Long> skuIds) {
        return R.ok(supplyService.materialSupply(skuIds));
    }
}
