package com.lingmao.scm.module.style.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lingmao.scm.module.style.entity.StyleSku;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.OffsetDateTime;
import java.util.List;

@Mapper
public interface StyleSkuMapper extends BaseMapper<StyleSku> {

    /**
     * 一键生成（RFC 005 §5）：按给的顺序插入，同一款式同一颜色同一尺码已有的跳过，返回新建了几个。
     * 调几次结果都一样；两个人同时点"生成"，后到的等前一个提交后全部跳过（uk_sty_sku_combo），不会建两份。
     * 自己写 SQL 绕开了自动填充，修改人、时间由调用方传
     */
    @Insert("""
            <script>
            INSERT INTO sty_style_sku (style_id, code, color_id, size_code, status, create_by, create_time, update_by, update_time)
            VALUES
            <foreach collection="skus" item="k" separator=",">
                (#{k.styleId}, #{k.code}, #{k.colorId}, #{k.sizeCode}, 1, #{userId}, #{now}, #{userId}, #{now})
            </foreach>
            ON CONFLICT (style_id, color_id, size_code) DO NOTHING
            </script>
            """)
    int insertMissing(@Param("skus") List<StyleSku> skus, @Param("userId") Long userId, @Param("now") OffsetDateTime now);
}
