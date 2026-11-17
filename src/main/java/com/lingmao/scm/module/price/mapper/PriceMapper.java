package com.lingmao.scm.module.price.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.lingmao.scm.module.price.dto.PriceQuery;
import com.lingmao.scm.module.price.dto.PriceRowVO;
import com.lingmao.scm.module.price.entity.Price;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;

/**
 * 价格档案（RFC 006）。取现价、列表的 SQL 在 resources/mapper/price/PriceMapper.xml。
 * 只插不改：插入用 BaseMapper.insert；能改的只有作废这一条（voidById），BaseMapper 自带的 update 不要用。
 * 不 JOIN 别的域的表：供应商、物料、款式的名称由各域的 Service 批量取（RFC 006 §7），物料现在的采购单位也由 basic 给，当参数传进来
 */
@Mapper
public interface PriceMapper extends BaseMapper<Price> {

    /**
     * 物料现价，一组（架构 §5.5）：生效日期不晚于 date、没作废、单位是 unit（物料现在的采购单位）；
     * 先找 skuId 的，没有再找物料层的（material_sku_id IS NULL）；同一层按生效日期、id 取最新的。没有返回 null
     */
    Price selectCurrentMaterial(@Param("supplierId") long supplierId, @Param("materialId") long materialId,
                                @Param("skuId") Long skuId, @Param("unit") String unit, @Param("date") LocalDate date);

    /** 物料现价，一批（DISTINCT ON），口径和上面一样。每个 key 最多一行，取不到的没有 */
    List<CurrentMaterialHit> selectCurrentMaterials(@Param("keys") Collection<MaterialUnitKey> keys, @Param("date") LocalDate date);

    /** 加工价现价：工厂 × 款式 × 加工方式，生效日期不晚于 date 的最新一条。没有返回 null */
    Price selectCurrentProcess(@Param("supplierId") long supplierId, @Param("styleId") long styleId,
                               @Param("processMode") String processMode, @Param("date") LocalDate date);

    /** 全部历史：生效日期新的在前，同一天后录的在前 */
    IPage<PriceRowVO> selectHistoryPage(IPage<PriceRowVO> page, @Param("q") PriceQuery query);

    /** 条件命中的物料价都是哪些物料：只看现价时要先拿它们现在的采购单位 */
    List<Long> selectMaterialIds(@Param("q") PriceQuery query);

    /**
     * 只看现价：每组一行（PriceQuery 的注释），带上一条的单价、以后才生效的下一条。
     * units：物料现在的采购单位，物料价只认这个单位的记录。分页要关掉 count 优化（optimizeCountSql = false）：
     * 语句是 WITH + DISTINCT ON + LATERAL，交给 MyBatis-Plus 拆了反而容易出错，直接 SELECT count(*) FROM (…) 最稳
     */
    IPage<PriceRowVO> selectCurrentPage(IPage<PriceRowVO> page, @Param("q") PriceQuery query,
                                        @Param("units") Collection<MaterialUnit> units, @Param("today") LocalDate today);

    /**
     * 作废（RFC 006 §3）：这张表唯一的一条 UPDATE，只改作废的四列。条件带 NOT voided，返回 0 = 已经作废了（或者不存在），
     * 两个人同时作废同一条，后到的改到 0 行
     */
    @Update("UPDATE prc_price SET voided = true, void_reason = #{reason}, void_by = #{by}, void_time = #{time} "
            + "WHERE id = #{id} AND NOT voided")
    int voidById(@Param("id") long id, @Param("reason") String reason, @Param("by") Long by, @Param("time") OffsetDateTime time);

    /** 单据反审核时作废它审核时记的价：来源是这张单、没作废的 */
    @Update("UPDATE prc_price SET voided = true, void_reason = #{reason}, void_by = #{by}, void_time = #{time} "
            + "WHERE source_type = #{sourceType} AND source_id = #{sourceId} AND NOT voided")
    int voidBySource(@Param("sourceType") String sourceType, @Param("sourceId") long sourceId, @Param("reason") String reason,
                     @Param("by") Long by, @Param("time") OffsetDateTime time);
}
