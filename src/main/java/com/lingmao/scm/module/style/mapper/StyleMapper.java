package com.lingmao.scm.module.style.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.lingmao.scm.module.style.dto.LookupRow;
import com.lingmao.scm.module.style.dto.StyleListVO;
import com.lingmao.scm.module.style.dto.StylePickQuery;
import com.lingmao.scm.module.style.dto.StylePickVO;
import com.lingmao.scm.module.style.dto.StyleQuery;
import com.lingmao.scm.module.style.entity.Style;
import com.lingmao.scm.security.LineScope;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Collection;
import java.util.List;

/**
 * 列表、导出、选款式的 SQL 在 resources/mapper/style/StyleMapper.xml：品类名、尺码组名只读 JOIN（架构 §3.2 规则 4）。
 * codes：关键字里有空格时拆出来的几个款号（大写），没有传 null；categoryIds：品类连同下级，不筛传 null
 */
@Mapper
public interface StyleMapper extends BaseMapper<Style> {

    IPage<StyleListVO> selectListPage(IPage<StyleListVO> page, @Param("q") StyleQuery query,
                                      @Param("codes") List<String> codes, @Param("categoryIds") Collection<Long> categoryIds,
                                      @Param("lines") LineScope lines);

    /** 同样的条件，不分页，只要 id，按款号排；导出用。limit 是最多几行 */
    List<Long> selectIds(@Param("q") StyleQuery query, @Param("codes") List<String> codes,
                         @Param("categoryIds") Collection<Long> categoryIds, @Param("limit") int limit,
                         @Param("lines") LineScope lines);

    /** 选款式：启用的款式（includeDraft 时加草稿）。brands：名称对得上关键字的品牌 value，关键字也算命中它们 */
    IPage<StylePickVO> selectPickPage(IPage<StylePickVO> page, @Param("q") StylePickQuery query, @Param("codes") List<String> codes,
                                      @Param("categoryIds") Collection<Long> categoryIds, @Param("brands") Collection<String> brands,
                                      @Param("lines") LineScope lines);

    /** 导入：按编码找物料（主力面料），不看状态；状态由 MaterialService 判断 */
    List<LookupRow> selectMaterialsByCodes(@Param("codes") Collection<String> codes);

    /** 导出：物料的编码 */
    List<LookupRow> selectMaterialsByIds(@Param("ids") Collection<Long> ids);

    /** 导入：按编码或简称找供应商（默认加工厂），不看状态（只当默认值，架构 A23）。name 是简称 */
    List<LookupRow> selectSuppliersByKeys(@Param("keys") Collection<String> keys);

    /** 导出：供应商的编码、简称 */
    List<LookupRow> selectSuppliersByIds(@Param("ids") Collection<Long> ids);

    /**
     * 锁住这个款式到事务结束，返回 id；不存在返回 null。加颜色、排颜色、生成 SKU、换尺码组前先锁：
     * 同一款的这些改动排队做（两个人同时加颜色，seq 不会撞；改尺码组和生成 SKU 不会交错）
     */
    @Select("SELECT id FROM sty_style WHERE id = #{id} FOR UPDATE")
    Long lockById(@Param("id") long id);
}
