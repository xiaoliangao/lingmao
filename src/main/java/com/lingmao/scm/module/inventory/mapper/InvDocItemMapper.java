package com.lingmao.scm.module.inventory.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lingmao.scm.module.inventory.entity.InvDocItem;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.Collection;
import java.util.List;

@Mapper
public interface InvDocItemMapper extends BaseMapper<InvDocItem> {
    /** 行数预算先于关联内容查询，返回一个计数而不是 5 万个 DTO。 */
    @org.apache.ibatis.annotations.Options(useCache = false, flushCache = org.apache.ibatis.annotations.Options.FlushCachePolicy.TRUE)
    @org.apache.ibatis.annotations.Select("<script>SELECT count(*) FROM (SELECT 1 FROM inv_doc_item WHERE doc_id IN "
            + "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach> "
            + "LIMIT #{limit}) bounded</script>")
    int countExportRows(@org.apache.ibatis.annotations.Param("ids") java.util.List<Long> ids,
                        @org.apache.ibatis.annotations.Param("limit") int limit);

    /** 每次只读 500 条明细；父顺序由已通过数据范围过滤的列表页给定。 */
    @org.apache.ibatis.annotations.Select("<script>SELECT * FROM inv_doc_item WHERE doc_id IN "
            + "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach> "
            + "ORDER BY CASE doc_id <foreach collection='ids' item='id' index='position'>WHEN #{id} THEN #{position} </foreach> END, seq, id "
            + "LIMIT 500 OFFSET #{offset}</script>")
    java.util.List<InvDocItem> selectExportPage(@org.apache.ibatis.annotations.Param("ids") java.util.List<Long> ids,
                                             @org.apache.ibatis.annotations.Param("offset") long offset);


    @Select("SELECT * FROM inv_doc_item WHERE doc_id = #{docId} ORDER BY seq, id")
    List<InvDocItem> selectByDoc(@Param("docId") long docId);

    @Select("<script>SELECT * FROM inv_doc_item WHERE doc_id IN "
            + "<foreach collection='docIds' item='id' open='(' separator=',' close=')'>#{id}</foreach> ORDER BY doc_id, seq, id</script>")
    List<InvDocItem> selectByDocs(@Param("docIds") Collection<Long> docIds);

    /** 单头 CAS 和来源锁均已持有；本次过账只分配当前行，不更改历史流水。 */
    @Update("UPDATE inv_doc_item SET qty=#{i.qty}, price=#{i.price}, amount=#{i.amount} WHERE id=#{i.id} AND doc_id=#{i.docId}")
    int applyPurchasePrecision(@Param("i") InvDocItem item);

    @Delete("DELETE FROM inv_doc_item WHERE doc_id = #{docId}")
    int deleteByDoc(@Param("docId") long docId);
}
