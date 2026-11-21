package com.lingmao.scm.module.purchase.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;

/**
 * 采购超收冲抵（RFC 014）：pur_over_offset 和采购行上的冲入、冲出两列。结果是 record，按列的顺序对构造参数，SELECT 的列顺序不能动。
 * 未到齐的条件要和部分索引 idx_pur_item_open（V53）一字不差
 */
@Mapper
public interface PurchaseOffsetMapper {

    /** 同一组的键：主体、供应商、品类线、SKU、采购单位、换算系数 */
    String GROUP_KEY = "concat_ws('|', o.company_id, o.supplier_id, COALESCE(o.product_line, ''), i.material_sku_id, i.purchase_unit, "
            + "i.conversion_factor)";
    String LINE_COLUMNS = "SELECT i.id, o.id AS po_id, o.no AS po_no, o.status AS po_status, " + GROUP_KEY + " AS group_key, "
            + "o.order_date, i.seq, o.audit_time, i.qty, i.received_qty, i.returned_qty, i.offset_in_qty, i.offset_out_qty ";

    /** 一条采购行冲抵要用的数 */
    record LineRow(Long id, Long poId, String poNo, String poStatus, String groupKey, LocalDate orderDate, Integer seq,
                   OffsetDateTime auditTime, BigDecimal qty, BigDecimal receivedQty, BigDecimal returnedQty, BigDecimal offsetInQty,
                   BigDecimal offsetOutQty) {
    }

    @Select("<script>" + LINE_COLUMNS + "FROM pur_order_item i JOIN pur_order o ON o.id = i.purchase_order_id WHERE i.id IN "
            + "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach></script>")
    List<LineRow> selectLines(@Param("ids") Collection<Long> ids);

    /** 和这几行同一组、没到齐的行（已审核、部分到货的采购单），不含它们自己 */
    @Select("<script>" + LINE_COLUMNS + "FROM pur_order_item p JOIN pur_order po ON po.id = p.purchase_order_id "
            + "JOIN pur_order o ON o.company_id = po.company_id AND o.supplier_id = po.supplier_id "
            + "AND o.product_line IS NOT DISTINCT FROM po.product_line AND o.status IN ('APPROVED', 'RUNNING') "
            + "JOIN pur_order_item i ON i.purchase_order_id = o.id AND i.material_sku_id = p.material_sku_id "
            + "AND i.purchase_unit = p.purchase_unit AND i.conversion_factor = p.conversion_factor "
            + "AND i.received_qty - i.returned_qty + i.offset_in_qty - i.offset_out_qty &lt; i.qty "
            + "WHERE p.id IN <foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach> "
            + "AND i.id NOT IN <foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach></script>")
    List<LineRow> selectOpenInGroups(@Param("ids") Collection<Long> ids);

    /** 一笔冲抵，带两头的采购单（锁、记操作记录用） */
    record OffsetRow(Long id, Long fromItemId, Long toItemId, BigDecimal qty, OffsetDateTime createTime, Long fromPoId, Long toPoId) {
    }

    /** 这几行上的冲抵：冲出去的、冲进来的 */
    @Select("<script>SELECT f.id, f.from_item_id, f.to_item_id, f.qty, f.create_time, fi.purchase_order_id AS from_po_id, "
            + "ti.purchase_order_id AS to_po_id FROM pur_over_offset f "
            + "JOIN pur_order_item fi ON fi.id = f.from_item_id JOIN pur_order_item ti ON ti.id = f.to_item_id "
            + "WHERE f.from_item_id IN <foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach> "
            + "OR f.to_item_id IN <foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach> "
            + "ORDER BY f.id</script>")
    List<OffsetRow> selectOfItems(@Param("ids") Collection<Long> ids);

    /** since = 多收那一刻：null 是现在；还回来再冲的照原来那笔的时间 */
    @Insert("INSERT INTO pur_over_offset (from_item_id, to_item_id, qty, create_time) VALUES (#{from}, #{to}, #{qty}, "
            + "COALESCE(CAST(#{since,jdbcType=TIMESTAMP_WITH_TIMEZONE} AS timestamptz), now()))")
    void insert(@Param("from") long fromItemId, @Param("to") long toItemId, @Param("qty") BigDecimal qty,
                @Param("since") OffsetDateTime since);

    @Update("UPDATE pur_over_offset SET qty = #{qty} WHERE id = #{id}")
    void updateQty(@Param("id") long id, @Param("qty") BigDecimal qty);

    @Delete("DELETE FROM pur_over_offset WHERE id = #{id}")
    void delete(@Param("id") long id);

    /** 采购行的冲入、冲出合计跟着改（调之前已经锁住采购单） */
    @Update("UPDATE pur_order_item SET offset_in_qty = offset_in_qty + #{in}, offset_out_qty = offset_out_qty + #{out} WHERE id = #{id}")
    void addOffset(@Param("id") long itemId, @Param("in") BigDecimal in, @Param("out") BigDecimal out);

    /** 采购单详情的"冲抵"：这张单的行和别的单之间的冲抵，带对面的单号和交货仓库 */
    record RefRow(Long itemId, String direction, Long purchaseOrderId, String purchaseNo, String warehouseName, BigDecimal qty) {
    }

    @Select("SELECT x.item_id, x.direction, o.id AS purchase_order_id, o.no AS purchase_no, w.name AS warehouse_name, x.qty "
            + "FROM (SELECT f.to_item_id AS item_id, 'IN' AS direction, f.from_item_id AS other_id, SUM(f.qty) AS qty "
            + "      FROM pur_over_offset f JOIN pur_order_item i ON i.id = f.to_item_id WHERE i.purchase_order_id = #{poId} "
            + "      GROUP BY f.to_item_id, f.from_item_id "
            + "      UNION ALL "
            + "      SELECT f.from_item_id, 'OUT', f.to_item_id, SUM(f.qty) "
            + "      FROM pur_over_offset f JOIN pur_order_item i ON i.id = f.from_item_id WHERE i.purchase_order_id = #{poId} "
            + "      GROUP BY f.from_item_id, f.to_item_id) x "
            + "JOIN pur_order_item oi ON oi.id = x.other_id JOIN pur_order o ON o.id = oi.purchase_order_id "
            + "JOIN bas_warehouse w ON w.id = o.warehouse_id ORDER BY x.item_id, x.direction, o.no")
    List<RefRow> selectRefsOfOrder(@Param("poId") long poId);
}
