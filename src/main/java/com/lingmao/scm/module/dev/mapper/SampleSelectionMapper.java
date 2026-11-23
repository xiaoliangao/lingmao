package com.lingmao.scm.module.dev.mapper;

import com.lingmao.scm.module.dev.entity.SampleColor;
import com.lingmao.scm.module.dev.entity.SampleOrderColor;
import com.lingmao.scm.module.dev.entity.SampleOrderSize;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Collection;
import java.util.List;

@Mapper
public interface SampleSelectionMapper {
    List<SampleColor> selectSampleColors(@Param("tenant") String tenant, @Param("companyId") long companyId,
                                         @Param("sampleId") long sampleId);
    List<SampleOrderColor> selectOrderColors(@Param("tenant") String tenant, @Param("companyId") long companyId,
                                             @Param("orderIds") Collection<Long> orderIds);
    List<SampleOrderSize> selectOrderSizes(@Param("tenant") String tenant, @Param("companyId") long companyId,
                                           @Param("orderIds") Collection<Long> orderIds);
    int deleteSampleColors(@Param("tenant") String tenant, @Param("companyId") long companyId,
                           @Param("sampleId") long sampleId);
    int deleteOrderColors(@Param("tenant") String tenant, @Param("companyId") long companyId,
                          @Param("orderId") long orderId);
    int deleteOrderSizes(@Param("tenant") String tenant, @Param("companyId") long companyId,
                         @Param("orderId") long orderId);
    int insertSampleColors(@Param("items") List<SampleColor> items);
    int insertOrderColors(@Param("items") List<SampleOrderColor> items);
    int insertOrderSizes(@Param("items") List<SampleOrderSize> items);
    int moveSampleColors(@Param("tenant") String tenant, @Param("sampleId") long sampleId,
                         @Param("oldCompany") long oldCompany, @Param("newCompany") long newCompany);
    int moveOrderColors(@Param("tenant") String tenant, @Param("sampleId") long sampleId,
                        @Param("oldCompany") long oldCompany, @Param("newCompany") long newCompany);
    int moveOrderSizes(@Param("tenant") String tenant, @Param("sampleId") long sampleId,
                       @Param("oldCompany") long oldCompany, @Param("newCompany") long newCompany);
}
