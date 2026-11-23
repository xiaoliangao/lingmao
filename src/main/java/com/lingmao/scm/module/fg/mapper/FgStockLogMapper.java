package com.lingmao.scm.module.fg.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.lingmao.scm.module.fg.dto.FgStockLogQuery;
import com.lingmao.scm.module.fg.dto.FgStockLogRowVO;
import com.lingmao.scm.module.fg.entity.FgStockLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Set;

@Mapper
public interface FgStockLogMapper extends BaseMapper<FgStockLog> {

    /** 调用方持有来源及单头锁；仅返回未冲销的原事件，历史已冲销不参与重算。 */
    @Select("SELECT l.* FROM fg_stock_log l WHERE l.company_id = #{companyId} AND l.fg_doc_id = #{docId} "
            + "AND l.reversal_of_log_id IS NULL AND NOT EXISTS "
            + "(SELECT 1 FROM fg_stock_log r WHERE r.reversal_of_log_id = l.id) "
            + "ORDER BY l.style_sku_id, l.company_id, l.warehouse_id, l.location_id, l.id")
    List<FgStockLog> reversibleOf(@Param("companyId") long companyId, @Param("docId") long docId);

    /** 固定按 (create_time,id) 向前游标，limit=页大小+1；不执行 OFFSET/COUNT。 */
    List<FgStockLogRowVO> selectSeek(@Param("q") FgStockLogQuery query, @Param("codes") List<String> codes,
            @Param("companyId") long companyId, @Param("afterTime") OffsetDateTime afterTime,
            @Param("afterId") Long afterId, @Param("limit") int limit);

    /** 这张单过过账没有（审核过一次就有日志，反审核也不删） */
    @Select("SELECT EXISTS (SELECT 1 FROM fg_stock_log WHERE fg_doc_id = #{docId})")
    boolean docPosted(@Param("docId") long docId);

    /** 几张单里过过账的（列表上决定给"删除"还是"作废"） */
    @Select("<script>SELECT DISTINCT fg_doc_id FROM fg_stock_log WHERE fg_doc_id IN "
            + "<foreach collection='docIds' item='id' open='(' separator=',' close=')'>#{id}</foreach></script>")
    Set<Long> postedDocs(@Param("docIds") Collection<Long> docIds);

    /** 库存日志（XML），新的在前 */
    IPage<FgStockLogRowVO> selectRowPage(IPage<FgStockLogRowVO> page, @Param("q") FgStockLogQuery query, @Param("codes") List<String> codes,
                                              @Param("companyId") long companyId);
}
