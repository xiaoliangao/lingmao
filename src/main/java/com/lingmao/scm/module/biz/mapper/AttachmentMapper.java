package com.lingmao.scm.module.biz.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lingmao.scm.module.biz.entity.Attachment;
import com.lingmao.scm.module.biz.entity.BizType;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Collection;
import java.util.List;

@Mapper
public interface AttachmentMapper extends BaseMapper<Attachment> {

    /**
     * 给一条记录的附件加锁，持有到事务结束：同一条记录的整组替换排队做。
     * 不加的话两个人同时替换，各自先删后插，PostgreSQL 的 DELETE 看不见对方还没提交的新行，两组图会叠在一起（AttachmentServiceImplIT）。
     * 锁不了记录本身那一行：它在别的域的表里，biz 碰不到；所以用按"类型 + id"算出来的 advisory lock，不对应任何一行
     */
    @Select("SELECT 1 FROM pg_advisory_xact_lock(hashtextextended(#{key}, 0))")
    Integer lockDoc(@Param("key") String key);

    /** 每条记录在这个分类下的第一个（主图）：一条 SQL 取一页列表的主图，DISTINCT ON 每个 doc_id 留排在最前的那行 */
    @Select("""
            <script>
            SELECT DISTINCT ON (doc_id) * FROM biz_attachment
            WHERE doc_type = #{type} AND category = #{category} AND doc_id IN
            <foreach collection="docIds" item="docId" open="(" separator="," close=")">#{docId}</foreach>
            ORDER BY doc_id, sort, id
            </script>
            """)
    List<Attachment> selectFirsts(@Param("type") BizType type, @Param("docIds") Collection<Long> docIds,
                                  @Param("category") String category);

    /** 一条记录的附件整批抄给另一条，顺序、分类、文件都一样 */
    @Insert("""
            INSERT INTO biz_attachment (doc_type, doc_id, category, sort, file_url, thumb_url, file_name, file_size,
                                        create_by, update_by)
            SELECT doc_type, #{toDocId}, category, sort, file_url, thumb_url, file_name, file_size, #{userId}, #{userId}
            FROM biz_attachment
            WHERE doc_type = #{type} AND doc_id = #{fromDocId}
            ORDER BY category, sort, id
            """)
    int copy(@Param("type") BizType type, @Param("fromDocId") long fromDocId, @Param("toDocId") long toDocId,
             @Param("userId") Long userId);
}
