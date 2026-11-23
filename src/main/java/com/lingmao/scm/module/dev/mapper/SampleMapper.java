package com.lingmao.scm.module.dev.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.lingmao.scm.module.dev.entity.Sample;
import com.lingmao.scm.module.dev.entity.SampleStatus;
import com.lingmao.scm.security.DataScope;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

/** Service 通过 SampleRepository 调用，tenant 一律由可信部署配置注入。 */
@Mapper
public interface SampleMapper extends BaseMapper<Sample> {
    Sample selectVisible(@Param("tenant") String tenant, @Param("companyId") long companyId,
                         @Param("id") long id, @Param("scope") DataScope scope);
    Sample selectIdentity(@Param("tenant") String tenant, @Param("companyId") long companyId,
                          @Param("id") long id, @Param("scope") DataScope scope);
    Sample selectByStyle(@Param("tenant") String tenant, @Param("companyId") long companyId,
                         @Param("styleId") long styleId, @Param("scope") DataScope scope);
    List<Sample> selectVisibleIds(@Param("tenant") String tenant, @Param("companyId") long companyId,
                                   @Param("ids") Collection<Long> ids, @Param("scope") DataScope scope);
    /** 内部反向引用先确认存在性，公开 Service 仍须按实际公司和 scope 鉴权。 */
    Sample selectConvertedSource(@Param("tenant") String tenant, @Param("styleId") long styleId);
    Sample lockVisible(@Param("tenant") String tenant, @Param("companyId") long companyId,
                       @Param("id") long id, @Param("scope") DataScope scope);
    IPage<SampleRow> selectRows(IPage<SampleRow> page, @Param("tenant") String tenant,
                               @Param("companyId") long companyId, @Param("q") SampleSearch query,
                               @Param("scope") DataScope scope, @Param("codes") List<String> codes,
                               @Param("keyword") String keyword, @Param("today") LocalDate today);
    List<StatusCount> selectCounts(@Param("tenant") String tenant, @Param("companyId") long companyId,
                                   @Param("q") SampleSearch query, @Param("scope") DataScope scope,
                                   @Param("codes") List<String> codes, @Param("keyword") String keyword,
                                   @Param("today") LocalDate today);
    List<SampleRow> selectHomePreview(@Param("tenant") String tenant, @Param("companyId") long companyId,
                                      @Param("scope") DataScope scope, @Param("kind") String kind,
                                      @Param("ownerId") Long ownerId, @Param("today") LocalDate today, @Param("limit") int limit);
    long selectHomeCount(@Param("tenant") String tenant, @Param("companyId") long companyId,
                         @Param("scope") DataScope scope, @Param("kind") String kind,
                         @Param("ownerId") Long ownerId, @Param("today") LocalDate today);
    int updateCas(@Param("tenant") String tenant, @Param("companyId") long currentCompanyId,
                  @Param("row") Sample row, @Param("expectedVersion") int expectedVersion,
                  @Param("expectedStatus") SampleStatus expectedStatus);
    int touchCas(@Param("tenant") String tenant, @Param("companyId") long companyId,
                 @Param("id") long id, @Param("expectedVersion") int expectedVersion,
                 @Param("expectedStatus") SampleStatus expectedStatus, @Param("actor") Long actor);
    int tombstoneCas(@Param("tenant") String tenant, @Param("companyId") long companyId,
                     @Param("id") long id, @Param("expectedVersion") int expectedVersion,
                     @Param("actor") Long actor);
    record StatusCount(String status, long total) { }
}
