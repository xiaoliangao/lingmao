package com.lingmao.scm.module.techpack.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.lingmao.scm.module.techpack.dto.StyleCostQuery;
import com.lingmao.scm.module.techpack.dto.StyleCostRowVO;
import com.lingmao.scm.module.techpack.entity.PackStage;
import com.lingmao.scm.module.techpack.entity.TechPack;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface TechPackMapper extends BaseMapper<TechPack> {

    /** 锁住这一版（删草稿前）：和保存、生效排队 */
    @Select("SELECT * FROM tp_tech_pack WHERE id = #{id} FOR UPDATE")
    TechPack lockById(@Param("id") long id);

    /** 这个款这个阶段最大的版本号，一个都没有是 0 */
    @Select("SELECT COALESCE(max(version), 0) FROM tp_tech_pack WHERE style_id = #{styleId} AND stage = #{stage}")
    int maxVersion(@Param("styleId") long styleId, @Param("stage") PackStage stage);

    /** 按款看成本（resources/mapper/techpack/TechPackMapper.xml）：每个款取生产已生效版，没有取研发已生效版 */
    IPage<StyleCostRowVO> selectStyleCostPage(IPage<StyleCostRowVO> page, @Param("q") StyleCostQuery query,
                                              @Param("codes") List<String> codes, @Param("categoryIds") List<Long> categoryIds);
}
