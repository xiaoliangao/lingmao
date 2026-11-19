package com.lingmao.scm.module.board.dto;

/**
 * 首页四个概况格子（GET /api/board/home/kpi，docs/api/06 §2.2，前端 types/home.ts 的 HomeKpi）：照数据范围、品类线数。
 * 没有那个模块列表权限的格子是 null，页面不显示这一格
 */
public record HomeKpi(Dev dev, Order order, Production production, Material material) {

    /** 开发款式（style:style:list）：没停用的款数、资料包草稿 */
    public record Dev(long styleCount, long packDraftCount) {
    }

    /**
     * 在手订单（order:order:list）：已审核、生产中的件数和张数；待审核、待排单；
     * 变更待审没有 order:change:list 是 null
     */
    public record Order(long qty, long count, long pendingCount, long unscheduledCount, Long changePendingCount) {
    }

    /** 在制生产（production:order:list）：已下单、生产中的件数（件数 − 已回货）和张数；已逾期、节点延期、有没关的异常 */
    public record Production(long qty, long count, long lateCount, long nodeLateCount, long issueCount) {
    }

    /** 在途物料（purchase:order:list）：没到齐的采购行、到料逾期、采购待审；算料待确认没有 purchase:mrp:list 是 null */
    public record Material(long transitLineCount, long arrivalLateCount, long purchasePendingCount, Long mrpDraftCount) {
    }
}
