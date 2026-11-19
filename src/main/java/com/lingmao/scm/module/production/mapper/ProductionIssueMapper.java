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

    /** 没关闭的（XML）：首页"异常待处理"，merchId 不为 null 时只要这个人跟的生产单 */
    IPage<OpenIssueVO> selectOpenPage(IPage<OpenIssueVO> page, @Param("merchId") Long merchId, @Param("scope") DataScope scope);

    @Select("SELECT * FROM prd_issue WHERE id = #{id} FOR UPDATE")
    ProductionIssue lockById(@Param("id") long id);
}
