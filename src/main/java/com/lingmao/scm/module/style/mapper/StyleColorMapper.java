package com.lingmao.scm.module.style.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lingmao.scm.module.style.entity.StyleColor;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface StyleColorMapper extends BaseMapper<StyleColor> {
    @org.apache.ibatis.annotations.Select("""
            <script>SELECT count(*) FROM (SELECT 1 FROM sty_style_color WHERE style_id IN
            <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
            LIMIT #{limit}) bounded</script>
            """)
    int countExportRows(@org.apache.ibatis.annotations.Param("ids") java.util.List<Long> ids,
                        @org.apache.ibatis.annotations.Param("limit") int limit);

}
