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
