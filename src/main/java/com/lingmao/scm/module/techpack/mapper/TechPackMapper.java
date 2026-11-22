package com.lingmao.scm.module.techpack.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.lingmao.scm.module.techpack.dto.PackDraftVO;
import com.lingmao.scm.module.techpack.dto.StyleCostQuery;
import com.lingmao.scm.module.techpack.dto.PackColorRef;
import com.lingmao.scm.module.techpack.dto.PackSizeRef;
import com.lingmao.scm.module.techpack.dto.StyleCostRowVO;
import com.lingmao.scm.module.techpack.entity.PackStage;
import com.lingmao.scm.module.techpack.entity.TechPack;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.ResultMap;

import java.util.List;
import java.util.Collection;

@Mapper
public interface TechPackMapper extends BaseMapper<TechPack> {

    /** 锁住这一版（删草稿前）：和保存、生效排队 */
    @Select("SELECT * FROM tp_tech_pack WHERE id = #{id} FOR UPDATE")
    @ResultMap("mybatis-plus_TechPack")
    TechPack lockById(@Param("id") long id);

    /** 这个款这个阶段最大的版本号，一个都没有是 0 */
    @Select("SELECT COALESCE(max(version), 0) FROM tp_tech_pack WHERE style_id = #{styleId} AND stage = #{stage}")
    int maxVersion(@Param("styleId") long styleId, @Param("stage") PackStage stage);

    /** 旧版无快照时保留包中已知尺码，不冒充完整的原始集合。 */
    @Select("SELECT size_code FROM tp_spec_value v JOIN tp_spec_point p ON p.id=v.spec_point_id WHERE p.tech_pack_id=#{id} "
            + "UNION SELECT size_code FROM tp_bom_item_size s JOIN tp_bom_item b ON b.id=s.bom_item_id WHERE b.tech_pack_id=#{id} "
            + "UNION SELECT size_code FROM tp_bom_item_sku s JOIN tp_bom_item b ON b.id=s.bom_item_id WHERE b.tech_pack_id=#{id} "
            + "AND size_code IS NOT NULL ORDER BY size_code")
    List<String> referencedSizes(@Param("id") long id);

    @Select("SELECT DISTINCT s.color_id FROM tp_bom_item_sku s JOIN tp_bom_item b ON b.id=s.bom_item_id "
            + "WHERE b.tech_pack_id=#{id} AND s.color_id IS NOT NULL ORDER BY s.color_id")
    List<Long> referencedColors(@Param("id") long id);

    List<PackColorRef> referencedColorsBatch(@Param("packIds") Collection<Long> packIds);

    List<PackSizeRef> referencedSizesBatch(@Param("packIds") Collection<Long> packIds);

    /** 共享主数据的草稿资料包，调用方校验模块权限；创建时间最早的先展示。 */
    IPage<PackDraftVO> selectDraftPage(IPage<PackDraftVO> page);

    /** 按款看成本（resources/mapper/techpack/TechPackMapper.xml）：每个款取生产已生效版，没有取研发已生效版 */
    IPage<StyleCostRowVO> selectStyleCostPage(IPage<StyleCostRowVO> page, @Param("q") StyleCostQuery query,
                                              @Param("codes") List<String> codes, @Param("categoryIds") List<Long> categoryIds);
}
