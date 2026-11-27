package com.lingmao.scm.module.board.service;

import com.lingmao.scm.common.page.PageResult;
import com.lingmao.scm.module.board.dto.FactoryPerfRow;
import com.lingmao.scm.module.board.dto.LineWipRow;
import com.lingmao.scm.module.board.dto.OrderMarginPage;
import com.lingmao.scm.module.board.dto.OrderMarginQuery;
import com.lingmao.scm.module.board.dto.PerfQuery;
import com.lingmao.scm.module.board.dto.PerfResult;
import com.lingmao.scm.module.board.dto.PurchasePerfRow;
import com.lingmao.scm.module.board.dto.StaleMaterialQuery;
import com.lingmao.scm.module.board.dto.StaleMaterialRow;

import java.util.List;

/** 报表（甲方 11-27 答 Q66、Q67、Q53，RFC 019 §3）：只读，现算 */
public interface ReportService {

    /** 物料供应商的采购到货及时率（按采购行） */
    PerfResult<PurchasePerfRow> purchasePerf(PerfQuery query);

    /** 加工厂的交期达成率（按生产单）、次品率（按回货） */
    PerfResult<FactoryPerfRow> factoryPerf(PerfQuery query);

    /** 各品类线在做的件数；month（yyyy-MM，不填是这个月）只管"这个月新排""这个月回货"两列 */
    List<LineWipRow> lineWip(String month);

    /** 呆滞料 */
    PageResult<StaleMaterialRow> staleMaterials(StaleMaterialQuery query);

    byte[] exportStaleMaterials(StaleMaterialQuery query);

    /** 多少天没动算呆滞（设置的默认，没设过是 90） */
    int staleDays();

    void setStaleDays(int days);

    /** 按默认天数，呆滞的有几条（首页、预警中心的数） */
    long staleCount();

    /** 订单毛利：销售额 − 按资料包核价估的成本 */
    OrderMarginPage orderMargins(OrderMarginQuery query);
}
