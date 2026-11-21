package com.lingmao.scm.module.production.mapper;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.lingmao.scm.module.production.dto.OrderProgressCounts;
import com.lingmao.scm.module.production.dto.OrderProgressQuery;
import com.lingmao.scm.module.production.dto.OrderProgressRowVO;
import com.lingmao.scm.security.DataScope;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;

/**
 * 订单进度总览（RFC 016 §1）：需求订单、明细只读 JOIN（架构 §3.2 规则 4，和 ProductionOrderMapper 一样），
 * 生产单的风险照 docs/api/05 §2.3 现算。数据范围按需求订单的列（制单、业务员、跟单）
 */
@Mapper
public interface OrderProgressMapper {

    IPage<OrderProgressRowVO> selectRowPage(IPage<OrderProgressRowVO> page, @Param("q") OrderProgressQuery query,
                                            @Param("scope") DataScope scope, @Param("today") LocalDate today,
                                            @Param("nearUntil") LocalDate nearUntil);

    OrderProgressCounts selectCounts(@Param("q") OrderProgressQuery query, @Param("scope") DataScope scope,
                                     @Param("today") LocalDate today, @Param("nearUntil") LocalDate nearUntil);
}
