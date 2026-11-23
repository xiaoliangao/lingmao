package com.lingmao.scm.module.dev.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lingmao.scm.module.dev.entity.SampleReview;
import com.lingmao.scm.module.dev.entity.SampleReviewer;
import com.lingmao.scm.security.DataScope;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Collection;
import java.util.List;

/** 审计记录只插；数据库拒绝 BaseMapper 的修改/删除旁路。 */
@Mapper
public interface SampleReviewMapper extends BaseMapper<SampleReview> {
    List<SampleReview> selectVisibleBatch(@Param("tenant") String tenant, @Param("companyId") long companyId,
                                          @Param("ids") Collection<Long> ids, @Param("scope") DataScope scope);
    List<SampleReview> selectByOrders(@Param("tenant") String tenant, @Param("companyId") long companyId,
                                      @Param("orderIds") Collection<Long> orderIds);
    List<SampleReviewer> selectReviewers(@Param("tenant") String tenant, @Param("companyId") long companyId,
                                        @Param("reviewIds") Collection<Long> reviewIds);
    int insertReviewers(@Param("items") List<SampleReviewer> items);
}
