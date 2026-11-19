package com.lingmao.scm.module.fg.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.lingmao.scm.module.fg.dto.FgDocQuery;
import com.lingmao.scm.module.fg.dto.FgDocRowVO;
import com.lingmao.scm.module.fg.dto.FgPendingReceiptQuery;
import com.lingmao.scm.module.fg.dto.FgPendingReceiptRowVO;
import com.lingmao.scm.module.fg.dto.FgShipOrderOptionVO;
import com.lingmao.scm.module.fg.entity.FgDoc;
import com.lingmao.scm.security.DataScope;
import com.lingmao.scm.security.LineScope;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

/**
 * 成品单据。回货单、生产单、订单、加工厂的显示字段在 XML 里只读 JOIN（架构 §3.2 规则 4）；
 * 能不能入、能不能发一律调 production、order 的方法（RFC 008 §6.2）
 */
@Mapper
public interface FgDocMapper extends BaseMapper<FgDoc> {

    /** 列表（XML）；codes 是关键字里的几个单号（有空格时），没有是 null */
    IPage<FgDocRowVO> selectRowPage(IPage<FgDocRowVO> page, @Param("q") FgDocQuery query, @Param("codes") List<String> codes,
                                    @Param("scope") DataScope scope);

    /** 改草稿、删除前锁住单头：和审核、反审核排队 */
    @Select("SELECT * FROM fg_doc WHERE id = #{id} FOR UPDATE")
    FgDoc lockById(@Param("id") long id);

    /** 一行明细的累计件数：回货明细的已入、订单行的已发 */
    record LineQty(Long lineId, int qty) {
    }

    /** 回货单每行的已入：没作废的成品入库单（草稿也算），不算 excludeDocId（改草稿时的这张单）。调之前已经锁住回货单 */
    @Select("<script>SELECT i.prd_receipt_item_id, SUM(i.qty) FROM fg_doc_item i JOIN fg_doc d ON d.id = i.doc_id "
            + "WHERE d.prd_receipt_id = #{receiptId} AND d.status &lt;&gt; 'VOIDED' "
            + "<if test='excludeDocId != null'>AND d.id &lt;&gt; #{excludeDocId}</if> GROUP BY i.prd_receipt_item_id</script>")
    List<LineQty> stockedByReceipt(@Param("receiptId") long receiptId, @Param("excludeDocId") Long excludeDocId);

    /** 订单每行的已发：已审核的发货出库，不算 excludeDocId */
    @Select("<script>SELECT i.order_item_id, SUM(i.qty) FROM fg_doc_item i JOIN fg_doc d ON d.id = i.doc_id "
            + "WHERE d.order_id = #{orderId} AND d.doc_type = 'SHIP_OUT' AND d.status = 'APPROVED' "
            + "<if test='excludeDocId != null'>AND d.id &lt;&gt; #{excludeDocId}</if> GROUP BY i.order_item_id</script>")
    List<LineQty> shippedByOrder(@Param("orderId") long orderId, @Param("excludeDocId") Long excludeDocId);

    /** 已发按 SKU（订单详情的"发 n"） */
    @Select("SELECT i.style_sku_id, SUM(i.qty) FROM fg_doc_item i JOIN fg_doc d ON d.id = i.doc_id "
            + "WHERE d.order_id = #{orderId} AND d.doc_type = 'SHIP_OUT' AND d.status = 'APPROVED' GROUP BY i.style_sku_id")
    List<LineQty> shippedBySku(@Param("orderId") long orderId);

    /**
     * 同一张订单的发货审核排队（RFC 008 §7）：事务级咨询锁，键 = (7401 << 32) | 订单 id，和别处的咨询锁错开。
     * 只有发同一张订单的排队，订单那一行不锁（不挡回货、变更）
     */
    @Select("SELECT 1 FROM pg_advisory_xact_lock((7401::bigint << 32) | #{orderId})")
    Integer lockShip(@Param("orderId") long orderId);

    /** 一张回货单上没作废的成品入库单（"良品都入过库了：CR…"） */
    @Select("SELECT * FROM fg_doc WHERE prd_receipt_id = #{receiptId} AND status <> 'VOIDED' ORDER BY id")
    List<FgDoc> selectByReceipt(@Param("receiptId") long receiptId);

    /** 几张回货单上没作废的成品入库单（生产单回货页签） */
    @Select("<script>SELECT * FROM fg_doc WHERE status &lt;&gt; 'VOIDED' AND prd_receipt_id IN "
            + "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach> ORDER BY id</script>")
    List<FgDoc> selectByReceipts(@Param("ids") Collection<Long> receiptIds);

    /** 挂这张订单的发货出库单（没作废的） */
    @Select("SELECT * FROM fg_doc WHERE order_id = #{orderId} AND status <> 'VOIDED' ORDER BY id")
    List<FgDoc> selectByOrder(@Param("orderId") long orderId);

    /** 详情的回货单信息（只读 JOIN） */
    record ReceiptHead(Long id, String no, Long prdOrderId, String prdOrderNo, String prdOrderKind, String factoryName,
                       LocalDate receiptDate) {
    }

    @Select("SELECT r.id, r.no, p.id, p.no, p.kind, s.short_name, r.receipt_date FROM prd_receipt r "
            + "JOIN prd_order p ON p.id = r.prd_order_id JOIN sup_supplier s ON s.id = p.factory_id WHERE r.id = #{id}")
    ReceiptHead receiptHead(@Param("id") long receiptId);

    /** 详情的订单信息（只读） */
    record OrderHead(Long id, String no, String deliveryReq) {
    }

    @Select("SELECT id, no, delivery_req FROM ord_order WHERE id = #{id}")
    OrderHead orderHead(@Param("id") long orderId);

    /** 草稿的"最多能填"只显示用：回货明细、订单行只读取（看得到这张单的人不一定看得到生产单、订单） */
    record ReceiptLine(Long id, Long styleSkuId, int qty, int defectQty) {
    }

    @Select("SELECT id, style_sku_id, qty, defect_qty FROM prd_receipt_item WHERE receipt_id = #{receiptId}")
    List<ReceiptLine> receiptLines(@Param("receiptId") long receiptId);

    record OrderLine(Long id, Long styleSkuId, int qty) {
    }

    @Select("SELECT id, style_sku_id, qty FROM ord_order_item WHERE order_id = #{orderId}")
    List<OrderLine> orderLines(@Param("orderId") long orderId);

    /** 导入明细：SKU 码（大写）→ id，停用的也在 */
    record SkuCode(Long id, String code) {
    }

    @Select("<script>SELECT id, upper(code) FROM sty_style_sku WHERE upper(code) IN "
            + "<foreach collection='codes' item='c' open='(' separator=',' close=')'>#{c}</foreach></script>")
    List<SkuCode> skuIdsByCodes(@Param("codes") Collection<String> codes);

    @Select("SELECT short_name FROM sup_supplier WHERE id = #{id}")
    String factoryName(@Param("id") long factoryId);

    /** 待入库（XML）：只列我的品类线里的生产单的回货 */
    IPage<FgPendingReceiptRowVO> selectPendingPage(IPage<FgPendingReceiptRowVO> page, @Param("q") FgPendingReceiptQuery query,
                                                   @Param("codes") List<String> codes, @Param("lines") LineScope lines);

    /** 待入库的张数（首页"回货待入成品仓"） */
    long countPending(@Param("lines") LineScope lines);

    /** 能发货的订单（XML），最多 50 条 */
    List<FgShipOrderOptionVO> selectShipOrders(@Param("keyword") String keyword, @Param("codes") List<String> codes,
                                               @Param("warehouseId") Long warehouseId, @Param("lines") LineScope lines);
}
