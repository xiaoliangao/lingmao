package com.lingmao.scm.module.production.service;

import com.lingmao.scm.common.page.PageResult;
import com.lingmao.scm.module.biz.dto.DocActionReq;
import com.lingmao.scm.module.production.dto.IssueOptionQuery;
import com.lingmao.scm.module.production.dto.IssueOptionVO;
import com.lingmao.scm.module.production.dto.MobileOrderRow;
import com.lingmao.scm.module.production.dto.ProductionDetailVO;
import com.lingmao.scm.module.production.dto.ProductionMaterialsVO;
import com.lingmao.scm.module.production.dto.ProductionQuery;
import com.lingmao.scm.module.production.dto.ProductionRowVO;
import com.lingmao.scm.module.production.dto.ProductionSaveReq;

import java.util.List;
import java.util.Map;

/**
 * 外发加工单、成品采购单（docs/api/05 §3.2、§3.4、§3.8）：一张表按 kind 分。数据范围"本人"看制单人、生产跟单是自己的。
 * 排单在 ScheduleService，进度在 ProductionNodeService，异常、回货、看板各有一个
 */
public interface ProductionOrderService {

    /** 列表：kind 必传；按风险分数、交期排（§2.3） */
    PageResult<ProductionRowVO> page(ProductionQuery query);

    /** 各状态几张，多一个 PARTIAL（生产中里回过货的）；条件和列表一样，不看 status、returned */
    Map<String, Long> counts(ProductionQuery query);

    /** 不存在、不在数据范围 404 */
    ProductionDetailVO detail(long id);

    /** 改草稿（只改单头），返回新的 version。不是草稿、version 对不上 1002 */
    int update(long id, ProductionSaveReq req);

    /** 删草稿：件数退回订单的待排单 */
    void delete(long id);

    /** submit、withdraw、reject、audit、unaudit、close、reopen、void（§3.2.5） */
    void act(long id, String action, DocActionReq req);

    byte[] export(ProductionQuery query);

    /** 单据公共接口用：不存在、不在数据范围 404 */
    void checkVisible(long id);

    /** 手机"我的跟单"：生产跟单是我、已下单或生产中，按风险排，不分页 */
    List<MobileOrderRow> myFollow();

    /** 给 04 选生产单 */
    PageResult<IssueOptionVO> issueOptions(IssueOptionQuery query);

    /** 领料页签：应领、已领、还差、物料仓库存，挂在单上的领料退料单 */
    ProductionMaterialsVO materials(long id);
}
