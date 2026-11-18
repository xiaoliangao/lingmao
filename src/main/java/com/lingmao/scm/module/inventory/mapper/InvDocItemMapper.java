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
