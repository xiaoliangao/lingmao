package com.lingmao.scm.module.production.service;

import com.lingmao.scm.module.production.dto.CapacityPreviewVO;
import com.lingmao.scm.module.production.dto.CapacityQuery;
import com.lingmao.scm.module.production.dto.CapacityRowVO;
import com.lingmao.scm.module.production.dto.CapacitySaveReq;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

/** 工厂产能（PRD 08.02，甲方 11-27 答 Q60）：登记、看，排单时查 */
public interface FactoryCapacityService {

    /** 看某个月各家厂、各品类用的产能和这个月交期的单占了多少 */
    List<CapacityRowVO> list(CapacityQuery query);

    /** 登记：同一家厂、同一个品类、同一个月已经有就覆盖；返回 id */
    Long save(CapacitySaveReq req);

    /** 删一条登记（删了按更早登记的算，都没有就不再查） */
    void delete(long id);

    /** 排单时看：这家厂做这个款、交期是 dueDate，还能排多少（每条管它的产能一行；没登记产能是空的）。excludeId 是改草稿时的这张单 */
    List<CapacityPreviewVO> preview(long factoryId, long styleId, LocalDate dueDate, Long excludeId);

    /**
     * 排单、改草稿的加工厂或交期时查（一个请求里的几块按顺序累计）：排不下的每条一句，没登记产能的厂不查。
     *
     * @param exclude 不算进已排的生产单（改草稿时是这张单自己）
     */
    List<String> check(List<Demand> demands, Collection<Long> exclude);

    /** 要排的一块：where 是报错的前缀（如"第 1 块（款号 / 厂）："） */
    record Demand(String where, long factoryId, long styleId, LocalDate dueDate, int qty) {
    }
}
