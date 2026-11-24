package com.lingmao.scm.module.fg.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lingmao.scm.module.fg.entity.FgDocItem;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Collection;
import java.util.List;

@Mapper
public interface FgDocItemMapper extends BaseMapper<FgDocItem> {
    /** 行数预算先于关联内容查询，返回一个计数而不是 5 万个 DTO。 */
    @org.apache.ibatis.annotations.Options(useCache = false, flushCache = org.apache.ibatis.annotations.Options.FlushCachePolicy.TRUE)
    @org.apache.ibatis.annotations.Select("<script>SELECT count(*) FROM (SELECT 1 FROM fg_doc_item WHERE doc_id IN "
            + "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach> "
            + "LIMIT #{limit}) bounded</script>")
    int countExportRows(@org.apache.ibatis.annotations.Param("ids") java.util.List<Long> ids,
                        @org.apache.ibatis.annotations.Param("limit") int limit);

    /** 每次只读 500 条明细；父顺序由已通过数据范围过滤的列表页给定。 */
    @org.apache.ibatis.annotations.Select("<script>SELECT * FROM fg_doc_item WHERE doc_id IN "
            + "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach> "
            + "ORDER BY CASE doc_id <foreach collection='ids' item='id' index='position'>WHEN #{id} THEN #{position} </foreach> END, seq, id "
            + "LIMIT 500 OFFSET #{offset}</script>")
    java.util.List<FgDocItem> selectExportPage(@org.apache.ibatis.annotations.Param("ids") java.util.List<Long> ids,
                                             @org.apache.ibatis.annotations.Param("offset") long offset);


    @Select("SELECT * FROM fg_doc_item WHERE doc_id = #{docId} ORDER BY seq, id")
    List<FgDocItem> selectByDoc(@Param("docId") long docId);

    @Select("<script>SELECT * FROM fg_doc_item WHERE doc_id IN "
            + "<foreach collection='docIds' item='id' open='(' separator=',' close=')'>#{id}</foreach> ORDER BY doc_id, seq, id</script>")
    List<FgDocItem> selectByDocs(@Param("docIds") Collection<Long> docIds);

    /** 保存/审核前重算保留旧行 ID；调用方限制最多 2000 行并严格比较返回行数。 */
    int updateExisting(@Param("docId") long docId, @Param("items") List<FgDocItem> items);

    @Delete("DELETE FROM fg_doc_item WHERE doc_id = #{docId}")
    int deleteByDoc(@Param("docId") long docId);
}
