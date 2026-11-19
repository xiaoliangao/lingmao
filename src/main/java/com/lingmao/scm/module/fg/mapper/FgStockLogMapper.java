package com.lingmao.scm.module.fg.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.lingmao.scm.module.fg.dto.FgStockLogQuery;
import com.lingmao.scm.module.fg.dto.FgStockLogRowVO;
import com.lingmao.scm.module.fg.entity.FgStockLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Collection;
import java.util.List;
import java.util.Set;

@Mapper
public interface FgStockLogMapper extends BaseMapper<FgStockLog> {

    /** 这张单过过账没有（审核过一次就有日志，反审核也不删） */
    @Select("SELECT EXISTS (SELECT 1 FROM fg_stock_log WHERE fg_doc_id = #{docId})")
    boolean docPosted(@Param("docId") long docId);

    /** 几张单里过过账的（列表上决定给"删除"还是"作废"） */
    @Select("<script>SELECT DISTINCT fg_doc_id FROM fg_stock_log WHERE fg_doc_id IN "
            + "<foreach collection='docIds' item='id' open='(' separator=',' close=')'>#{id}</foreach></script>")
    Set<Long> postedDocs(@Param("docIds") Collection<Long> docIds);

    /** 库存日志（XML），新的在前 */
    IPage<FgStockLogRowVO> selectRowPage(IPage<FgStockLogRowVO> page, @Param("q") FgStockLogQuery query, @Param("codes") List<String> codes);
}
