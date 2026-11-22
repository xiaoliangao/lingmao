package com.lingmao.scm.module.production.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.lingmao.scm.module.production.dto.OpenIssueVO;
import com.lingmao.scm.module.production.entity.ProductionIssue;
import com.lingmao.scm.security.DataScope;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ProductionIssueMapper extends BaseMapper<ProductionIssue> {

    /** 没关闭的在前，同样的按上报时间倒序 */
    @Select("SELECT * FROM prd_issue WHERE prd_order_id = #{prdOrderId} ORDER BY (status = 'CLOSED'), id DESC")
    List<ProductionIssue> selectByOrder(@Param("prdOrderId") long prdOrderId);

    /** 首页未关闭异常：先按生产单数据范围和负责人过滤，再按上报时间分页。 */
    IPage<OpenIssueVO> selectOpenPage(IPage<OpenIssueVO> page, @Param("merchId") Long merchId, @Param("scope") DataScope scope);

    @Select("SELECT * FROM prd_issue WHERE id = #{id} FOR UPDATE")
    ProductionIssue lockById(@Param("id") long id);
}
