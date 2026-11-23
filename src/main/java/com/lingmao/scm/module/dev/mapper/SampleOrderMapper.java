package com.lingmao.scm.module.dev.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lingmao.scm.module.dev.entity.SampleOrder;
import com.lingmao.scm.module.dev.entity.SampleOrderStatus;
import com.lingmao.scm.security.DataScope;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Collection;
import java.util.List;

@Mapper
public interface SampleOrderMapper extends BaseMapper<SampleOrder> {
    SampleOrder selectLatest(@Param("tenant") String tenant, @Param("companyId") long companyId,
                             @Param("sampleId") long sampleId);
    SampleOrder lockLatest(@Param("tenant") String tenant, @Param("companyId") long companyId,
                           @Param("sampleId") long sampleId);
    SampleOrder selectScoped(@Param("tenant") String tenant, @Param("companyId") long companyId,
                             @Param("id") long id);
    List<SampleOrder> selectVersions(@Param("tenant") String tenant, @Param("companyId") long companyId,
                                      @Param("sampleId") long sampleId, @Param("beforeVersion") Integer beforeVersion,
                                      @Param("limit") int limit);
    List<SampleOrder> selectCurrentBySamples(@Param("tenant") String tenant, @Param("companyId") long companyId,
                                            @Param("sampleIds") Collection<Long> sampleIds);
    List<SampleOrder> selectVisibleBatch(@Param("tenant") String tenant, @Param("companyId") long companyId,
                                         @Param("ids") Collection<Long> ids, @Param("scope") DataScope scope);
    int updateCas(@Param("tenant") String tenant, @Param("companyId") long companyId,
                  @Param("row") SampleOrder row, @Param("expectedVersion") int expectedVersion,
                  @Param("expectedStatus") SampleOrderStatus expectedStatus);
    /** 只由已锁父样衣的公司变更调用；CAS 内容版本仍由动作单独维护。 */
    int moveCompany(@Param("tenant") String tenant, @Param("sampleId") long sampleId,
                    @Param("oldCompany") long oldCompany, @Param("newCompany") long newCompany);
}
