package com.lingmao.scm.module.biz.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lingmao.scm.module.biz.entity.BizType;
import com.lingmao.scm.module.biz.entity.DocNotice;
import com.lingmao.scm.security.DataScope;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;

@Mapper
public interface DocNoticeMapper extends BaseMapper<DocNotice> {

    record PendingGroup(BizType docType, Long docId, OffsetDateTime latest, long noticesTotal) {
    }

    /** 原聚合查询：回填未就绪/显式回退及历史计划对照时使用。 */
    List<PendingGroup> selectPendingGroups(@Param("types") Collection<BizType> types, @Param("ownerId") Long ownerId,
                                           @Param("scope") DataScope scope, @Param("cursorTime") OffsetDateTime cursorTime,
                                           @Param("cursorType") BizType cursorType, @Param("cursorId") Long cursorId,
                                           @Param("oldest") boolean oldest, @Param("limit") int limit);

    /** 同一授权范围的完整单据组数，不随游标改变。 */
    long countPendingGroups(@Param("types") Collection<BizType> types, @Param("ownerId") Long ownerId,
                            @Param("scope") DataScope scope);

    /** 同事务摘要，仍先按当前单据头授权；tenantKey 仅来自可信部署配置。 */
    List<PendingGroup> selectSummaryPendingGroups(@Param("types") Collection<BizType> types, @Param("ownerId") Long ownerId,
                                                  @Param("scope") DataScope scope, @Param("cursorTime") OffsetDateTime cursorTime,
                                                  @Param("cursorType") BizType cursorType, @Param("cursorId") Long cursorId,
                                                  @Param("oldest") boolean oldest, @Param("limit") int limit,
                                                  @Param("tenantKey") String tenantKey);

    /** 摘要中的完整授权组数，独立于游标。 */
    long countSummaryPendingGroups(@Param("types") Collection<BizType> types, @Param("ownerId") Long ownerId,
                                   @Param("scope") DataScope scope, @Param("tenantKey") String tenantKey);

    /** 当前页每张单最多 100 条最新提醒；重新应用权限，不能用伪造的 group 绕过数据范围。 */
    List<DocNotice> selectPendingPreviews(@Param("groups") Collection<PendingGroup> groups, @Param("ownerId") Long ownerId,
                                         @Param("scope") DataScope scope, @Param("limit") int limit);
}
