package com.lingmao.scm.module.production.controller;

import com.lingmao.scm.common.result.R;
import com.lingmao.scm.module.production.dto.FactoryOrderVO;
import com.lingmao.scm.module.production.dto.MobileOrderRow;
import com.lingmao.scm.module.production.service.ProductionOrderService;
import com.lingmao.scm.security.FactoryAccess;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 工厂账号看本厂的生产单（甲方 11-27 答 Q02，RFC 019 §7，docs/api/05 §8）。只有工厂账号能调（scope:FACTORY）。
 * 报进度、登记裁床数用生产单原来的接口（/api/production-orders/{id}/nodes/{nodeId}/reports、/cut-qty），
 * 那几个接口标了 @FactoryAccess，单子归不归这家由 ProductionSupport.visible 管
 */
@Tag(name = "工厂账号")
@RestController
@RequestMapping("/api/factory-orders")
@RequiredArgsConstructor
public class FactoryOrderController {

    private final ProductionOrderService productionOrderService;

    @Operation(summary = "工厂账号：本厂已下单、生产中的生产单，按风险排（不分页）")
    @GetMapping
    @PreAuthorize("hasAuthority('scope:FACTORY')")
    @FactoryAccess
    public R<List<MobileOrderRow>> list() {
        return R.ok(productionOrderService.factoryOrders());
    }

    @Operation(summary = "工厂账号：本厂的一张生产单——节点、颜色尺码明细（件数、裁床数、已回）、能做的动作；不给单价、订单、审批、异常")
    @GetMapping("/{id:\\d+}")
    @PreAuthorize("hasAuthority('scope:FACTORY')")
    @FactoryAccess
    public R<FactoryOrderVO> detail(@PathVariable("id") long id) {
        return R.ok(productionOrderService.factoryDetail(id));
    }
}
