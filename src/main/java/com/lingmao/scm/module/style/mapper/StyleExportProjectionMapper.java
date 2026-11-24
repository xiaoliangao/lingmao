package com.lingmao.scm.module.style.mapper;

import com.lingmao.scm.module.style.dto.StyleMatrixExportRows.ColorAxis;
import com.lingmao.scm.module.style.dto.StyleMatrixExportRows.SizeAxis;
import com.lingmao.scm.module.style.dto.StyleMatrixExportRows.SkuCell;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** 固定 500 行的导出读取；清空一级缓存，动态表头预检也不积存所有页。 */
@Mapper
public interface StyleExportProjectionMapper {
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    @Select("<script>SELECT s.id AS style_id, i.size_code FROM sty_style s "
            + "JOIN bas_size_group_item i ON i.size_group_id=s.size_group_id WHERE s.id IN "
            + "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach> "
            + "ORDER BY s.code,s.id,i.seq,i.id LIMIT 500 OFFSET #{offset}</script>")
    List<SizeAxis> sizes(@Param("ids") List<Long> ids, @Param("offset") long offset);

    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    @Select("<script>SELECT sc.id AS relation_id,s.id AS style_id,s.code AS style_code,s.name AS style_name,"
            + "sc.color_id,c.name AS color_name FROM sty_style s JOIN sty_style_color sc ON sc.style_id=s.id "
            + "LEFT JOIN bas_color c ON c.id=sc.color_id WHERE s.id IN "
            + "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach> "
            + "ORDER BY s.code,s.id,sc.seq,sc.id LIMIT 500 OFFSET #{offset}</script>")
    List<ColorAxis> colors(@Param("ids") List<Long> ids, @Param("offset") long offset);

    /** 包含历史孤立 SKU，保留完整款式 SKU 预算的口径。 */
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    @Select("<script>SELECT k.id AS sku_id,0::bigint AS relation_id,k.style_id,k.color_id,k.size_code "
            + "FROM sty_style_sku k WHERE k.style_id IN "
            + "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach> "
            + "ORDER BY k.style_id,k.id LIMIT 500 OFFSET #{offset}</script>")
    List<SkuCell> skus(@Param("ids") List<Long> ids, @Param("offset") long offset);

    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    @Select("<script>SELECT k.id AS sku_id,sc.id AS relation_id,k.style_id,k.color_id,k.size_code "
            + "FROM sty_style_color sc JOIN sty_style_sku k ON k.style_id=sc.style_id AND k.color_id=sc.color_id "
            + "WHERE sc.id IN <foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach> "
            + "ORDER BY sc.id,k.id LIMIT 500 OFFSET #{offset}</script>")
    List<SkuCell> cells(@Param("ids") List<Long> ids, @Param("offset") long offset);
}
