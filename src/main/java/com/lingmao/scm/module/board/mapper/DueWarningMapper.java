package com.lingmao.scm.module.board.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.util.List;

/**
 * 每天的货期预警（钉钉推送，RFC 019 §4）：按负责人数。口径和预警中心、首页的数一样：
 * 生产单看 ProductionOrderMapper 的 riskJoin（已下单、生产中；预计交期没有用约定交期），采购行看待到料（预计到货没有用交期）。
 * 只数在用的账号。SQL 在 mapper/board/DueWarningMapper.xml
 */
@Mapper
public interface DueWarningMapper {

    /** 一个跟单员：已逾期、7 天内到期、节点延期的生产单张数 */
    record ProductionDigest(long userId, int late, int near, int nodeLate) {
    }

    /** 一个采购员：到料逾期的采购行数 */
    record ArrivalDigest(long userId, int late) {
    }

    List<ProductionDigest> selectProduction(@Param("today") LocalDate today, @Param("nearUntil") LocalDate nearUntil);

    List<ArrivalDigest> selectArrival(@Param("today") LocalDate today);
}
