package com.lingmao.scm.module.production.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lingmao.scm.module.production.entity.ProductionNode;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Collection;
import java.util.List;

@Mapper
public interface ProductionNodeMapper extends BaseMapper<ProductionNode> {

    @Select("SELECT * FROM prd_node WHERE prd_order_id = #{prdOrderId} ORDER BY seq, id")
    List<ProductionNode> selectByOrder(@Param("prdOrderId") long prdOrderId);

    @Select("<script>SELECT * FROM prd_node WHERE prd_order_id IN "
            + "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach> ORDER BY prd_order_id, seq, id</script>")
    List<ProductionNode> selectByOrders(@Param("ids") Collection<Long> prdOrderIds);

    @Delete("DELETE FROM prd_node WHERE prd_order_id = #{prdOrderId}")
    void deleteByOrder(@Param("prdOrderId") long prdOrderId);
}
