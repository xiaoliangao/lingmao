package com.lingmao.scm.module.production.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lingmao.scm.module.production.entity.ProductionIssue;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ProductionIssueMapper extends BaseMapper<ProductionIssue> {

    /** 没关闭的在前，同样的按上报时间倒序 */
    @Select("SELECT * FROM prd_issue WHERE prd_order_id = #{prdOrderId} ORDER BY (status = 'CLOSED'), id DESC")
    List<ProductionIssue> selectByOrder(@Param("prdOrderId") long prdOrderId);

    @Select("SELECT * FROM prd_issue WHERE id = #{id} FOR UPDATE")
    ProductionIssue lockById(@Param("id") long id);
}
