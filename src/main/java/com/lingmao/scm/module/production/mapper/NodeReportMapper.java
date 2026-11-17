package com.lingmao.scm.module.production.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lingmao.scm.module.production.entity.NodeReport;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface NodeReportMapper extends BaseMapper<NodeReport> {

    /** 一个节点的汇报记录，新的在前 */
    @Select("SELECT * FROM prd_node_report WHERE node_id = #{nodeId} ORDER BY id DESC")
    List<NodeReport> selectByNode(@Param("nodeId") long nodeId);

    /** 一张单的全部报工（节点、id 倒序）：详情的报过几次、照片 */
    @Select("SELECT * FROM prd_node_report WHERE prd_order_id = #{prdOrderId} ORDER BY node_id, id DESC")
    List<NodeReport> selectByOrder(@Param("prdOrderId") long prdOrderId);

    @Select("SELECT EXISTS (SELECT 1 FROM prd_node_report WHERE prd_order_id = #{prdOrderId})")
    boolean existsByOrder(@Param("prdOrderId") long prdOrderId);
}
