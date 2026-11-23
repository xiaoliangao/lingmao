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
                                    @Param("scope") DataScope scope, @Param("companyId") long companyId);

    /** 改草稿、删除前锁住单头：和审核、反审核排队 */
    @Select("SELECT * FROM fg_doc WHERE id = #{id} FOR UPDATE")
    FgDoc lockById(@Param("id") long id);

    /** 一行明细的累计件数：回货明细的已入、订单行的已发 */
    record LineQty(Long lineId, long qty) {
    }

    /** 回货单每行的已入：没作废的成品入库单（草稿也算），不算 excludeDocId（改草稿时的这张单）。调之前已经锁住回货单 */
    @Select("<script>SELECT i.prd_receipt_item_id, SUM(i.qty) FROM fg_doc_item i JOIN fg_doc d ON d.id = i.doc_id "
            + "WHERE d.company_id = #{companyId} AND d.prd_receipt_id = #{receiptId} AND d.status &lt;&gt; 'VOIDED' "
            + "<if test='excludeDocId != null'>AND d.id &lt;&gt; #{excludeDocId}</if> GROUP BY i.prd_receipt_item_id</script>")
    List<LineQty> stockedByReceipt(@Param("receiptId") long receiptId, @Param("excludeDocId") Long excludeDocId,
                                   @Param("companyId") long companyId);

    /** 订单每行的已发：已审核的发货出库，不算 excludeDocId */
    @Select("<script>SELECT i.order_item_id, SUM(i.qty) FROM fg_doc_item i JOIN fg_doc d ON d.id = i.doc_id "
            + "WHERE d.company_id = #{companyId} AND d.order_id = #{orderId} AND d.doc_type = 'SHIP_OUT' AND d.status = 'APPROVED' "
            + "<if test='excludeDocId != null'>AND d.id &lt;&gt; #{excludeDocId}</if> GROUP BY i.order_item_id</script>")
    List<LineQty> shippedByOrder(@Param("orderId") long orderId, @Param("excludeDocId") Long excludeDocId,
                                 @Param("companyId") long companyId);

    /** 已发按 SKU（订单详情的"发 n"） */
    @Select("SELECT i.style_sku_id, SUM(i.qty) FROM fg_doc_item i JOIN fg_doc d ON d.id = i.doc_id "
            + "WHERE d.company_id = #{companyId} AND d.order_id = #{orderId} AND d.doc_type = 'SHIP_OUT' AND d.status = 'APPROVED' GROUP BY i.style_sku_id")
    List<LineQty> shippedBySku(@Param("orderId") long orderId, @Param("companyId") long companyId);

    /** 详情只算当前最多 2000 行的累计量，不把来源的所有历史行载入 JVM。 */
    @Select("<script>SELECT i.prd_receipt_item_id, SUM(i.qty) FROM fg_doc_item i JOIN fg_doc d ON d.id=i.doc_id "
            + "WHERE d.company_id=#{companyId} AND d.prd_receipt_id=#{receiptId} AND d.status &lt;&gt; 'VOIDED' "
            + "<if test='excludeDocId != null'>AND d.id &lt;&gt; #{excludeDocId}</if> "
            + "<choose><when test='lineIds != null and !lineIds.isEmpty()'>AND i.prd_receipt_item_id IN "
            + "<foreach collection='lineIds' item='id' open='(' separator=',' close=')'>#{id}</foreach>"
            + "</when><otherwise>AND FALSE</otherwise></choose> GROUP BY i.prd_receipt_item_id LIMIT 2001</script>")
    List<LineQty> stockedByReceiptLines(@Param("receiptId") long receiptId, @Param("excludeDocId") Long excludeDocId,
            @Param("companyId") long companyId, @Param("lineIds") Collection<Long> lineIds);

    @Select("<script>SELECT i.order_item_id, SUM(i.qty) FROM fg_doc_item i JOIN fg_doc d ON d.id=i.doc_id "
            + "WHERE d.company_id=#{companyId} AND d.order_id=#{orderId} AND d.doc_type='SHIP_OUT' AND d.status='APPROVED' "
            + "<if test='excludeDocId != null'>AND d.id &lt;&gt; #{excludeDocId}</if> "
            + "<choose><when test='lineIds != null and !lineIds.isEmpty()'>AND i.order_item_id IN "
            + "<foreach collection='lineIds' item='id' open='(' separator=',' close=')'>#{id}</foreach>"
            + "</when><otherwise>AND FALSE</otherwise></choose> GROUP BY i.order_item_id LIMIT 2001</script>")
    List<LineQty> shippedByOrderLines(@Param("orderId") long orderId, @Param("excludeDocId") Long excludeDocId,
            @Param("companyId") long companyId, @Param("lineIds") Collection<Long> lineIds);

    record ReceiptLineOccupancy(Long lineId, long reservedQty, long postedQty) { }

    @Select("SELECT i.prd_receipt_item_id AS line_id, "
            + "COALESCE(SUM(i.qty) FILTER (WHERE d.status='DRAFT'),0) AS reserved_qty, "
            + "COALESCE(SUM(i.qty) FILTER (WHERE d.status='APPROVED'),0) AS posted_qty "
            + "FROM fg_doc d JOIN fg_doc_item i ON i.doc_id=d.id "
            + "WHERE d.company_id=#{companyId} AND d.prd_receipt_id=#{receiptId} AND d.status<>'VOIDED' "
            + "GROUP BY i.prd_receipt_item_id")
    List<ReceiptLineOccupancy> selectReceiptLineOccupancy(@Param("receiptId") long receiptId,
                                                       @Param("companyId") long companyId);

    /** 来源数量合计必须含全部有效占用，不能因调用者看不到某张 FG 单就把额度腾出来。 */
    record ReceiptOccupancy(Long receiptId, long reservedQty, long postedQty) { }
    record ReceiptDocument(Long receiptId, Long docId, String no, String status, long qty) { }
    record ReceiptDocumentCount(Long receiptId, long total) { }

    List<ReceiptOccupancy> selectReceiptOccupancy(@Param("ids") Collection<Long> receiptIds,
                                                @Param("companyId") long companyId);
    List<ReceiptDocumentCount> selectReceiptDocumentCounts(@Param("ids") Collection<Long> receiptIds,
            @Param("scope") DataScope scope, @Param("companyId") long companyId);
    /** 一次 SQL 为每个已授权来源取最多 20 条可见单据；完整记录通过 receiptId 单据列表分页。 */
    List<ReceiptDocument> selectReceiptDocumentPreviews(@Param("ids") Collection<Long> receiptIds,
            @Param("scope") DataScope scope, @Param("companyId") long companyId);

    /** 详情的回货单信息（只读 JOIN） */
    record ReceiptHead(Long id, String no, Long prdOrderId, String prdOrderNo, String prdOrderKind, String factoryName,
                       LocalDate receiptDate, Long companyId) {
    }

    @Select("SELECT r.id, r.no, p.id AS prd_order_id, p.no AS prd_order_no, p.kind AS prd_order_kind, "
            + "s.short_name AS factory_name, r.receipt_date, r.company_id FROM prd_receipt r "
            + "JOIN prd_order p ON p.id = r.prd_order_id JOIN sup_supplier s ON s.id = p.factory_id WHERE r.id = #{id}")
    ReceiptHead receiptHead(@Param("id") long receiptId);

    /** 详情的订单信息（只读） */
    record OrderHead(Long id, String no, String deliveryReq, Long companyId) {
    }

    @Select("SELECT id, no, delivery_req, company_id FROM ord_order WHERE id = #{id}")
    OrderHead orderHead(@Param("id") long orderId);

    /** 草稿的"最多能填"只显示用：回货明细、订单行只读取（看得到这张单的人不一定看得到生产单、订单） */
    record ReceiptLine(Long id, Long styleSkuId, int qty, int defectQty) {
    }

    @Select("<script>SELECT i.id, i.style_sku_id, i.qty, i.defect_qty FROM prd_receipt_item i "
            + "JOIN prd_receipt r ON r.id=i.receipt_id WHERE i.receipt_id=#{receiptId} AND r.company_id=#{companyId} "
            + "<choose><when test='lineIds != null and !lineIds.isEmpty()'>AND i.id IN "
            + "<foreach collection='lineIds' item='id' open='(' separator=',' close=')'>#{id}</foreach>"
            + "</when><otherwise>AND FALSE</otherwise></choose> ORDER BY i.id LIMIT 2001</script>")
    List<ReceiptLine> receiptLines(@Param("receiptId") long receiptId, @Param("lineIds") Collection<Long> lineIds,
                                   @Param("companyId") long companyId);

    record OrderLine(Long id, Long styleSkuId, int qty) {
    }

    @Select("<script>SELECT i.id, i.style_sku_id, i.qty FROM ord_order_item i "
            + "JOIN ord_order o ON o.id=i.order_id WHERE i.order_id=#{orderId} AND o.company_id=#{companyId} "
            + "<choose><when test='lineIds != null and !lineIds.isEmpty()'>AND i.id IN "
            + "<foreach collection='lineIds' item='id' open='(' separator=',' close=')'>#{id}</foreach>"
            + "</when><otherwise>AND FALSE</otherwise></choose> ORDER BY i.id LIMIT 2001</script>")
    List<OrderLine> orderLines(@Param("orderId") long orderId, @Param("lineIds") Collection<Long> lineIds,
                               @Param("companyId") long companyId);

    /** 导入明细：SKU 码（大写）→ id，停用的也在 */
    record SkuCode(Long id, String code) {
    }

    @Select("<script>SELECT id, upper(code) FROM sty_style_sku WHERE upper(code) IN "
            + "<foreach collection='codes' item='c' open='(' separator=',' close=')'>#{c}</foreach></script>")
    List<SkuCode> skuIdsByCodes(@Param("codes") Collection<String> codes);

    @Select("SELECT short_name FROM sup_supplier WHERE id = #{id}")
    String factoryName(@Param("id") long factoryId);

    /** 待入库（XML）：公司过滤不可省；调用者须具有成品来源操作权限 */
    IPage<FgPendingReceiptRowVO> selectPendingPage(IPage<FgPendingReceiptRowVO> page, @Param("q") FgPendingReceiptQuery query,
                                                   @Param("codes") List<String> codes, @Param("scope") DataScope scope, @Param("companyId") long companyId);

    /** 待入库的张数（首页"回货待入成品仓"） */
    long countPending(@Param("scope") DataScope scope, @Param("companyId") long companyId);

    /** 能发货的订单（XML），最多 50 条 */
    List<FgShipOrderOptionVO> selectShipOrders(@Param("keyword") String keyword, @Param("codes") List<String> codes,
                                               @Param("warehouseId") Long warehouseId, @Param("scope") DataScope scope, @Param("companyId") long companyId);
}
