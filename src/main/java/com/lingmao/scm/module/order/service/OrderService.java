package com.lingmao.scm.module.order.service;

import com.lingmao.scm.common.excel.ImportResult;
import com.lingmao.scm.common.page.PageQuery;
import com.lingmao.scm.common.page.PageResult;
import com.lingmao.scm.module.biz.dto.DocActionReq;
import com.lingmao.scm.module.biz.dto.DocSaved;
import com.lingmao.scm.module.order.dto.OrderAuditReq;
import com.lingmao.scm.module.order.dto.OrderCreateReq;
import com.lingmao.scm.module.order.dto.OrderDetailVO;
import com.lingmao.scm.module.order.dto.OrderForProduction;
import com.lingmao.scm.module.order.dto.OrderImportHead;
import com.lingmao.scm.module.order.dto.OrderQuery;
import com.lingmao.scm.module.order.dto.OrderRowVO;
import com.lingmao.scm.module.order.dto.OrderSaveReq;
import com.lingmao.scm.module.order.dto.OrderStyleOptionVO;
import com.lingmao.scm.module.order.dto.ProductionProgress;
import com.lingmao.scm.module.order.dto.StyleOrderRowVO;
import com.lingmao.scm.module.order.entity.OrderChange;
import com.lingmao.scm.module.order.entity.OrderChangeItem;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/** 需求订单（docs/api/02 §3.2–§3.11）。数据范围"本人"看制单人、业务员、跟单员是自己的 */
public interface OrderService {

    PageResult<OrderRowVO> page(OrderQuery query);

    /** 各状态几张，条件和列表一样（不看 status） */
    Map<String, Long> counts(OrderQuery query);

    /** 详情：一个款一块，件数 0 的行（变更单减掉的）不出。不存在、不在数据范围 404 */
    OrderDetailVO detail(long id);

    /** 画矩阵要的款式信息，按请求的顺序；不能下单的款照样返回，problem 写原因 */
    List<OrderStyleOptionVO> styleOptions(List<Long> styleIds);

    /** 新建草稿：只查格式，资料包、款式状态在提交时查 */
    DocSaved create(OrderCreateReq req);

    /** 保存草稿，整单覆盖，返回新的 version。不是草稿、version 对不上 1002 */
    int update(long id, OrderSaveReq req);

    /** 删草稿；审核后提过变更单的只能作废 */
    void delete(long id);

    /** submit、withdraw、reject、unaudit、close、reopen、void；审核用 audit */
    void act(long id, String action, DocActionReq req);

    /** 审核一级（填承诺交期）；最后一级审过锁定每个款现在生效的生产 BOM */
    void audit(long id, OrderAuditReq req);

    /** 款式详情的"需求订单"页签 */
    PageResult<StyleOrderRowVO> byStyle(long styleId, PageQuery page);

    byte[] importTemplate();

    /** 导入：同一个分单号一张草稿订单；有一行错整批不导（1003） */
    ImportResult importExcel(OrderImportHead head, MultipartFile file);

    /** 一张订单一行，条件同列表，多一个 ids */
    byte[] export(OrderQuery query);

    /** 单据公共接口用：不存在、不在数据范围 404 */
    void checkVisible(long id);

    /**
     * 变更单审核时写回订单（同一个事务，docs/api/02 §3.14）：锁住订单，核对每一处的"原"还是订单现在的值（对不上 1002），
     * 改件数、加 SKU、单价、资料包版本、要求交期，重算合计，版本号 +1；取消订单的有下游单据 1005，没有就变成已取消
     */
    void applyChange(OrderChange change, List<OrderChangeItem> items);

    // ---------- 给生产（docs/api/05 §3.9） ----------

    /**
     * 锁住订单（FOR UPDATE）再取：排单、生产单的动作要回写订单的，都先调它。锁的顺序是订单 → 生产单，
     * 和变更单写回、取消订单排队。不查数据范围（生产跟单不一定看得到订单）。不存在 404
     */
    OrderForProduction lockForProduction(long orderId);

    /** 同上，不加锁：排单对话框带出、取价这种只读的 */
    OrderForProduction getForProduction(long orderId);

    /** 已排产加减：正数占上（排单），负数退回（删、作废生产单草稿）。调之前已经 lockForProduction */
    void addScheduled(Map<Long, Integer> deltaByItem);

    /** 已回货加减：登记回货、作废回货单。调之前已经 lockForProduction */
    void addReturned(Map<Long, Integer> deltaByItem);

    /**
     * 按生产进度重算订单状态（已审核、生产中、已完成之间换），别的状态不动；变了写一条操作记录，cause 是哪张生产单干了什么。
     * 每个 SKU 都排满了、没作废的生产单都完成或结案了是已完成，有审核过的生产单是生产中，不然是已审核。调之前已经 lockForProduction
     */
    void syncProduction(long orderId, ProductionProgress progress, String cause);
}
