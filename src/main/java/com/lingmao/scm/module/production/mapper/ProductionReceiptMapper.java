package com.lingmao.scm.module.production.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lingmao.scm.module.production.entity.ProductionReceipt;
import com.lingmao.scm.security.DataScope;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Collection;
import java.util.List;

@Mapper
public interface ProductionReceiptMapper extends BaseMapper<ProductionReceipt> {

    /** 含作废的，新的在前 */
    @Select("SELECT * FROM prd_receipt WHERE prd_order_id = #{prdOrderId} ORDER BY id DESC")
    List<ProductionReceipt> selectByOrder(@Param("prdOrderId") long prdOrderId);

    /** 成品来源与作废回货单共用的首级行锁；之后才能锁 FG 单头和余额。 */
    @Select("SELECT * FROM prd_receipt WHERE id = #{id} FOR UPDATE")
    ProductionReceipt lockById(@Param("id") long id);

    /** 成品批量关联的只读来源白名单；业务层先核对请求数量，再校验整批返回 ID。 */
    record StockSource(Long id, Long companyId, long goodQty) { }

    @Select("""
            <script>
            SELECT r.id,r.company_id,
                   CASE WHEN r.status='APPROVED' THEN COALESCE(SUM(i.qty-i.defect_qty),0) ELSE 0::bigint END AS good_qty
            FROM prd_receipt r JOIN prd_order p ON p.id=r.prd_order_id AND p.company_id=r.company_id
            LEFT JOIN prd_receipt_item i ON i.receipt_id=r.id
            WHERE r.company_id=#{companyId}
            <choose><when test="ids != null and !ids.isEmpty()">AND r.id IN
            <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
            </when><otherwise>AND FALSE</otherwise></choose>
            <if test="scope.self">AND (p.create_by=#{scope.userId} OR p.merch_id=#{scope.userId})</if>
            GROUP BY r.id,r.company_id
            </script>
            """)
    List<StockSource> selectStockSources(@Param("ids") Collection<Long> ids,
            @Param("scope") DataScope scope, @Param("companyId") long companyId);
}
