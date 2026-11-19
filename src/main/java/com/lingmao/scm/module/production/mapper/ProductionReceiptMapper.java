package com.lingmao.scm.module.production.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lingmao.scm.module.production.entity.ProductionReceipt;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ProductionReceiptMapper extends BaseMapper<ProductionReceipt> {

    /** 含作废的，新的在前 */
    @Select("SELECT * FROM prd_receipt WHERE prd_order_id = #{prdOrderId} ORDER BY id DESC")
    List<ProductionReceipt> selectByOrder(@Param("prdOrderId") long prdOrderId);

    /** 成品入库、作废回货单排队用 */
    @Select("SELECT * FROM prd_receipt WHERE id = #{id} FOR UPDATE")
    ProductionReceipt lockById(@Param("id") long id);
}
