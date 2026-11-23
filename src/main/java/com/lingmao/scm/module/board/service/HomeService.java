package com.lingmao.scm.module.board.service;

import com.lingmao.scm.module.board.dto.DeliveryRow;
import com.lingmao.scm.module.board.dto.HomeKpi;
import com.lingmao.scm.module.board.dto.HomeTodoQuery;
import com.lingmao.scm.module.board.dto.HomeTodos;
import com.lingmao.scm.module.board.dto.UpstreamList;
import com.lingmao.scm.module.board.dto.UpstreamQuery;

import java.util.List;

/**
 * 首页（docs/api/06）：只读，每一块按当前用户的模块权限、数据范围出，问各域的 Service（和点进去的列表同一个查询），
 * 不自己写跨域 SQL。登录就能调，没有那个模块权限的块是 null 或者不出
 */
public interface HomeService {

    /** 四个概况格子 */
    default HomeKpi kpi(){return kpi(null);}
    HomeKpi kpi(Long companyId);

    /** 待办分组和"其他提醒"的数 */
    HomeTodos todos(HomeTodoQuery query);

    /** 近期交期：7 天内要交的和已经逾期的需求订单、生产单，交期早的在前，最多 8 条 */
    List<DeliveryRow> deliveries();

    /** 上游已变更、待处理（/upstream） */
    UpstreamList upstream(UpstreamQuery query);
}
