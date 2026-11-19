package com.lingmao.scm.module.board.controller;

import com.lingmao.scm.common.result.R;
import com.lingmao.scm.module.board.dto.DeliveryRow;
import com.lingmao.scm.module.board.dto.HomeKpi;
import com.lingmao.scm.module.board.dto.HomeTodoQuery;
import com.lingmao.scm.module.board.dto.HomeTodos;
import com.lingmao.scm.module.board.dto.OrderReview;
import com.lingmao.scm.module.board.dto.UpstreamList;
import com.lingmao.scm.module.board.dto.UpstreamQuery;
import com.lingmao.scm.module.board.service.HomeService;
import com.lingmao.scm.module.board.service.OrderReviewService;
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
 * 首页、上游已变更、订单审核的评审信息（docs/api/06、02 §3.8）：只读的跨域汇总。
 * 首页几块登录就能调，不加专门的权限码：每一块在 Service 里按模块列表权限、数据范围、品类线出，没权限的块是 null 或者不出。
 * 评审信息和订单详情一样要 order:order:list
 */
@Tag(name = "首页")
@RestController
@RequestMapping("/api/board")
@RequiredArgsConstructor
public class BoardController {

    private final HomeService homeService;
    private final OrderReviewService reviewService;

    @Operation(summary = "概况四个格子：开发款式、在手订单、在制生产、在途物料；没有模块权限的是 null")
    @GetMapping("/home/kpi")
    public R<HomeKpi> kpi() {
        return R.ok(homeService.kpi());
    }

    @Operation(summary = "待办分组和其他提醒的数；数据范围是全部的人能按 mine、ownerId 筛负责人")
    @GetMapping("/home/todos")
    public R<HomeTodos> todos(HomeTodoQuery query) {
        return R.ok(homeService.todos(query));
    }

    @Operation(summary = "近期交期：7 天内要交的和已经逾期的需求订单、生产单，最多 8 条")
    @GetMapping("/home/deliveries")
    public R<List<DeliveryRow>> deliveries() {
        return R.ok(homeService.deliveries());
    }

    @Operation(summary = "上游已变更、待处理：一张下游单据一行，按最新一条提醒倒序")
    @GetMapping("/upstream")
    public R<UpstreamList> upstream(UpstreamQuery query) {
        return R.ok(homeService.upstream(query));
    }

    @Operation(summary = "需求订单审核对话框的评审信息：资料包、缺料粗算、默认加工厂在手；看得到这张订单才行")
    @PreAuthorize("hasAuthority('order:order:list')")
    @GetMapping("/orders/{id}/review")
    public R<OrderReview> review(@PathVariable long id) {
        return R.ok(reviewService.review(id));
    }
}
