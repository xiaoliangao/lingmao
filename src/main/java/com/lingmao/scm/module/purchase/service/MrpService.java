package com.lingmao.scm.module.purchase.service;

import com.lingmao.scm.common.page.PageQuery;
import com.lingmao.scm.common.page.PageResult;
import com.lingmao.scm.module.biz.dto.DocActionReq;
import com.lingmao.scm.module.biz.dto.DocSaved;
import com.lingmao.scm.module.purchase.dto.MrpCalcDetailVO;
import com.lingmao.scm.module.purchase.dto.MrpCalcQuery;
import com.lingmao.scm.module.purchase.dto.MrpCalcRowVO;
import com.lingmao.scm.module.purchase.dto.MrpCreateReq;
import com.lingmao.scm.module.purchase.dto.MrpItemForm;
import com.lingmao.scm.module.purchase.dto.MrpOrderOptionVO;
import com.lingmao.scm.module.purchase.dto.OrderKittingVO;
import com.lingmao.scm.module.purchase.dto.RemarkReq;
import com.lingmao.scm.module.purchase.dto.ReservePctReq;
import com.lingmao.scm.module.purchase.dto.ReservePctVO;
import com.lingmao.scm.module.purchase.entity.MrpCalc;
import com.lingmao.scm.module.purchase.entity.MrpCalcItem;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 算料单（docs/api/03 §3.2）、齐料状态（§3.5） */
public interface MrpService {

    PageResult<MrpCalcRowVO> page(MrpCalcQuery query);

    MrpCalcDetailVO detail(long id);

    /** 不在数据范围里 404（单据公共接口用） */
    void checkVisible(long id);

    /** 原成功操作的来源对象仍须在当前可见范围内；不重验可生成状态。 */
    void checkSourceOrdersVisible(Collection<Long> ids);

    PageResult<MrpOrderOptionVO> orderOptions(String keyword, PageQuery page);

    DocSaved create(MrpCreateReq req);

    MrpCalcDetailVO updateItem(long id, long itemId, MrpItemForm form);

    MrpCalcDetailVO removeItem(long id, long itemId, int version);

    void updateRemark(long id, RemarkReq req);

    void confirm(long id, DocActionReq req);

    void close(long id, DocActionReq req);

    DocSaved recalc(long id, DocActionReq req);

    void delete(long id);

    List<ReservePctVO> reservePcts();

    void saveReservePcts(List<ReservePctReq> list);

    List<OrderKittingVO> kitting(List<Long> orderIds);

    /**
     * 生成采购单前（PurchaseOrderServiceImpl.fromMrp 调，同一个事务）：锁住算料单，草稿先确认（没过 1000），
     * 查勾的行能不能转（docs/api/03 §3.3.7），返回这几行
     */
    List<MrpCalcItem> lockForConvert(long id, List<Long> itemIds, int version);

    /** 生成采购单以后：已转采购重算、版本号 +1、记一条操作记录 */
    void afterConvert(MrpCalc calc, List<MrpCalcItem> items, String text);

    /**
     * 采购单改、删、作废之前锁住这些算料行所在的算料单（按 id 排）：锁的顺序是算料单 → 采购单，和确认、重新算料一样，免得互相等。
     * 返回锁住的算料单 id
     */
    List<Long> lockCalcsOfItems(Collection<Long> calcItemIds);

    /** 采购单改、删、作废以后：这些算料行的已转采购按采购行重算（调之前已经 lockCalcsOfItems） */
    void syncConverted(Collection<Long> calcItemIds);

    MrpCalc getById(long id);

    // ---------- 给生产（docs/api/05 §3.4.3，架构 §5.4） ----------

    /** 和这几张订单一起算料的订单：它们所在的已确认算料单里的全部订单（含它们自己）。回写已领前用它拿全要算的订单 */
    Set<Long> calcPeers(Collection<Long> orderIds);

    /**
     * 生产领料、退料过账以后回写：issuedByOrder 是订单 → 物料 SKU → 从物料仓已领（领料出库 − 退料入库，库存单位），要传齐 calcPeers 给的订单。
     * 已确认的算料单每一行的已领 = 它的订单加起来；doneOrders 是排满了、生产单都领齐了的订单，一张算料单的订单都在里面就自动关闭（ISSUED）。
     * 有重新算料的草稿的不自动关（不然确认草稿时关不了上一张）
     */
    void syncIssued(Map<Long, Map<Long, BigDecimal>> issuedByOrder, Set<Long> doneOrders);
}
