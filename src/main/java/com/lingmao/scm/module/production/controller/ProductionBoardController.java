package com.lingmao.scm.module.production.controller;

import com.lingmao.scm.common.page.PageResult;
import com.lingmao.scm.common.result.R;
import com.lingmao.scm.module.production.dto.BoardCards;
import com.lingmao.scm.module.production.dto.BoardFactoryRow;
import com.lingmao.scm.module.production.dto.BoardQuery;
import com.lingmao.scm.module.production.dto.ProductionRowVO;
import com.lingmao.scm.module.production.service.ProductionBoardService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 生产看板（docs/api/05 §3.7）：只看已下单、生产中的单，照数据范围 */
@Tag(name = "生产看板")
@RestController
@RequestMapping("/api/production-board")
@RequiredArgsConstructor
public class ProductionBoardController {

    /** 聚合查询开关仅由服务端构造，不能通过 GET 参数触发旁路或改变导出/COUNT 口径。 */
    @org.springframework.web.bind.annotation.InitBinder("boardQuery")
    void protectInternalQueryFields(org.springframework.web.bind.WebDataBinder binder) {
        binder.setDisallowedFields("factoryIds*");
    }

    private final ProductionBoardService boardService;

    @Operation(summary = "数字卡：在制、待开工、临期、已逾期、节点延期、有异常、本月回货")
    @GetMapping("/cards")
    @PreAuthorize("hasAuthority('production:board:list')")
    public R<BoardCards> cards(@Valid @org.springframework.web.bind.annotation.ModelAttribute("boardQuery") BoardQuery query) {
        return R.ok(boardService.cards(query));
    }

    @Operation(summary = "列表：card 和卡片口径一样；按风险、交期排")
    @GetMapping("/orders")
    @PreAuthorize("hasAuthority('production:board:list')")
    public R<PageResult<ProductionRowVO>> orders(@Valid @org.springframework.web.bind.annotation.ModelAttribute("boardQuery") BoardQuery query) {
        return R.ok(boardService.orders(query));
    }

    @Operation(summary = "按工厂：合作中的成衣厂、加工厂都列（没活的也列），加上停用了还有活的；按在制件数排")
    @GetMapping("/factories")
    @PreAuthorize("hasAuthority('production:board:list')")
    public R<List<BoardFactoryRow>> factories(@Valid @org.springframework.web.bind.annotation.ModelAttribute("boardQuery") BoardQuery query) {
        return R.ok(boardService.factories(query));
    }
}
