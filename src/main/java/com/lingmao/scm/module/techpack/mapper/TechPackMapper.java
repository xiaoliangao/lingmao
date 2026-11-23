package com.lingmao.scm.module.techpack.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.lingmao.scm.module.techpack.dto.PackDraftVO;
import com.lingmao.scm.module.techpack.dto.StyleCostQuery;
import com.lingmao.scm.module.techpack.dto.PackColorRef;
import com.lingmao.scm.module.techpack.dto.PackSizeRef;
import com.lingmao.scm.module.techpack.dto.StyleCostRowVO;
import com.lingmao.scm.module.techpack.entity.PackStage;
import com.lingmao.scm.module.techpack.entity.PackStatus;
import com.lingmao.scm.module.techpack.entity.TechPack;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.ResultMap;

import java.time.OffsetDateTime;
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
    /** 只读识别 owner，随后必须由 Service 授权并先锁真实父头，再锁资料包、重查身份。 */
    OwnerIdentity ownerIdentity(@Param("id") long id);

    /** 样衣详情版本列表；调用者已通过 SamplePackOwner，limit 必须有界。 */
    List<SamplePackHead> selectSamplePacks(@Param("tenant") String tenant, @Param("companyId") long companyId,
                                    @Param("sampleId") long sampleId, @Param("beforeVersion") Integer beforeVersion, @Param("limit") int limit);

    /** 调用前持有样衣父锁；返回包始终按 id 升序，不能先锁包再等样衣。 */
    List<SamplePackIdentity> lockSamplePacks(@Param("tenant") String tenant, @Param("companyId") long companyId,
                                  @Param("sampleId") long sampleId, @Param("limit") int limit);

    long countSamplePacks(@Param("tenant") String tenant, @Param("companyId") long companyId,
                          @Param("sampleId") long sampleId);

    int maxSampleVersion(@Param("tenant") String tenant, @Param("companyId") long companyId,
                         @Param("sampleId") long sampleId);

    boolean existsForSample(@Param("tenant") String tenant, @Param("companyId") long companyId,
                            @Param("sampleId") long sampleId);

    /** 先锁全体样衣包并校验读取上限；移交不改内容，version_lock 原子 +1，结果须与锁定行数相等。 */
    int adoptSamplePacks(@Param("tenant") String tenant, @Param("companyId") long companyId,
                         @Param("sampleId") long sampleId, @Param("styleId") long styleId,
                         @Param("actor") Long actor);

    /** 锁定/移交只读这八个小字段，绝不把每版 ownerSnapshot 放进内存。 */
    record SamplePackIdentity(Long id, Long styleId, Long sampleId, Long companyId,
                              PackStage stage, PackStatus status, Integer version, Integer versionLock) { }

    /** 版本条投影；成本/快照需要时另经授权读取被选中的单个包。 */
    record SamplePackHead(Long id, Long styleId, Long sampleId, Long companyId, PackStage stage,
                          PackStatus status, Integer version, Integer versionLock, String versionNote,
                          Long sourcePackId, Integer sourceVersion, Long publishBy, OffsetDateTime publishTime,
                          Long createBy, OffsetDateTime createTime, OffsetDateTime updateTime) { }

    record OwnerIdentity(Long id, Long styleId, Long sampleId, Long companyId, Integer versionLock) { }
}
