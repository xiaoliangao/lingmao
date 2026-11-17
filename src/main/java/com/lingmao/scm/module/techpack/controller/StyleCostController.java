package com.lingmao.scm.module.techpack.controller;

import com.lingmao.scm.common.page.PageResult;
import com.lingmao.scm.common.result.R;
import com.lingmao.scm.module.techpack.dto.StyleCostQuery;
import com.lingmao.scm.module.techpack.dto.StyleCostRowVO;
import com.lingmao.scm.module.techpack.service.TechPackCostService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 按款看成本（docs/api/01 §3.13，菜单"核价"） */
@Tag(name = "按款看成本")
@RestController
@RequestMapping("/api/style-costs")
@RequiredArgsConstructor
public class StyleCostController {

    private final TechPackCostService costService;

    @Operation(summary = "一个款一行：取生产已生效版，没有取研发已生效版；款号倒序")
    @GetMapping
    @PreAuthorize("hasAuthority('techpack:cost:query')")
    public R<PageResult<StyleCostRowVO>> page(@Valid StyleCostQuery query) {
        return R.ok(costService.styleCosts(query));
    }
}
