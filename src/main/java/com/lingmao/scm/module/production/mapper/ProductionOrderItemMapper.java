package com.lingmao.scm.module.production.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lingmao.scm.module.production.entity.ProductionOrderItem;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.Collection;
import java.util.List;

@Mapper
public interface ProductionOrderItemMapper extends BaseMapper<ProductionOrderItem> {

    @Select("SELECT * FROM prd_order_item WHERE prd_order_id = #{prdOrderId} ORDER BY seq, id")
    List<ProductionOrderItem> selectByOrder(@Param("prdOrderId") long prdOrderId);

    @Select("<script>SELECT * FROM prd_order_item WHERE prd_order_id IN "
            + "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach> ORDER BY prd_order_id, seq, id</script>")
    List<ProductionOrderItem> selectByOrders(@Param("ids") Collection<Long> prdOrderIds);

    /**
     * 回写已回货、次品（登记回货是正数，作废回货单是负数）。条件更新：加完良品不超过 maxQty（⌊件数或裁床数 × 1.05⌋）、回货不小于 0、次品不超过回货，
     * 这一次的次品和回货同号、不比回货多（不能拿已有的良品充次品），不满足返回 0（调用方按 1004 / 程序错处理）。调之前已经锁住生产单
     */
    @Update("UPDATE prd_order_item SET returned_qty = returned_qty + #{qty}, defect_qty = defect_qty + #{defect} "
            + "WHERE id = #{id} AND returned_qty + #{qty} >= 0 AND returned_qty + #{qty} - defect_qty - #{defect} <= #{maxQty} "
            + "AND defect_qty + #{defect} BETWEEN 0 AND returned_qty + #{qty} "
            + "AND ((#{qty} >= 0 AND #{defect} BETWEEN 0 AND #{qty}) OR (#{qty} < 0 AND #{defect} BETWEEN #{qty} AND 0))")
    int addReturned(@Param("id") long id, @Param("qty") int qty, @Param("defect") int defect, @Param("maxQty") int maxQty);

    /** 登记裁床数（null = 清掉）。调之前已经锁住生产单 */
    @Update("UPDATE prd_order_item SET cut_qty = #{cutQty} WHERE id = #{id}")
    void updateCut(@Param("id") long id, @Param("cutQty") Integer cutQty);

    @Delete("DELETE FROM prd_order_item WHERE prd_order_id = #{prdOrderId}")
    void deleteByOrder(@Param("prdOrderId") long prdOrderId);
}
