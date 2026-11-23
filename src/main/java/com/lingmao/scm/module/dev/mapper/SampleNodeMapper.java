package com.lingmao.scm.module.dev.mapper;

import com.lingmao.scm.module.dev.entity.SampleNode;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

@Mapper
public interface SampleNodeMapper {
    List<SampleNode> selectByOrders(@Param("tenant") String tenant, @Param("companyId") long companyId,
                                    @Param("orderIds") Collection<Long> orderIds);
    List<SampleNode> lockByOrder(@Param("tenant") String tenant, @Param("companyId") long companyId,
                                 @Param("orderId") long orderId);
    int insertAll(@Param("items") List<SampleNode> items);
    int updatePlans(@Param("tenant") String tenant, @Param("companyId") long companyId,
                    @Param("orderId") long orderId, @Param("items") List<SampleNode> items,
                    @Param("actor") Long actor);
    int setDone(@Param("tenant") String tenant, @Param("companyId") long companyId,
                @Param("orderId") long orderId, @Param("id") long id,
                @Param("doneDate") LocalDate doneDate, @Param("actor") Long actor);
    int reassignUndone(@Param("tenant") String tenant, @Param("companyId") long companyId,
                       @Param("orderId") long orderId, @Param("ownerId") Long ownerId, @Param("actor") Long actor);
}
