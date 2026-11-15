package com.lingmao.scm.module.basic.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.lingmao.scm.module.basic.dto.MaterialDupVO;
import com.lingmao.scm.module.basic.dto.MaterialListVO;
import com.lingmao.scm.module.basic.dto.MaterialPickQuery;
import com.lingmao.scm.module.basic.dto.MaterialPickVO;
import com.lingmao.scm.module.basic.dto.MaterialQuery;
import com.lingmao.scm.module.basic.dto.SupplierBrief;
import com.lingmao.scm.module.basic.entity.Material;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Collection;
import java.util.List;

/**
 * 列表、导出、查重、选 SKU 的 SQL 在 resources/mapper/basic/MaterialMapper.xml。
 * 默认供应商的名称、导入时按编码找供应商：只读查 sup_supplier（架构 §3.2 规则 4），basic 的 Java 代码不调 supplier
 */
@Mapper
public interface MaterialMapper extends BaseMapper<Material> {

    /** codes：关键字里有空格时拆出来的几个编码（大写），没有传 null */
    IPage<MaterialListVO> selectListPage(IPage<MaterialListVO> page, @Param("q") MaterialQuery query,
                                         @Param("codes") List<String> codes);

    /** 同样的条件，不分页，只要 id，按编码排；导出用。limit 是最多几行 */
    List<Long> selectIds(@Param("q") MaterialQuery query, @Param("codes") List<String> codes, @Param("limit") int limit);

    /** 几个物料的列表行（详情页取分类名、供应商名用） */
    List<MaterialListVO> selectListByIds(@Param("ids") Collection<Long> ids);

    /** 建档查重：同分类同名、同默认供应商同货号，最多 10 条（RFC 003 §7.2） */
    List<MaterialDupVO> selectDuplicates(@Param("categoryId") Long categoryId, @Param("name") String name,
                                         @Param("supplierId") Long supplierId, @Param("itemNo") String supplierItemNo,
                                         @Param("excludeId") Long excludeId);

    /** 选物料 SKU：启用物料的启用 SKU */
    IPage<MaterialPickVO> selectPickPage(IPage<MaterialPickVO> page, @Param("q") MaterialPickQuery query);

    /** 按编码找供应商（导入），不看状态 */
    List<SupplierBrief> selectSuppliersByCodes(@Param("codes") Collection<String> codes);

    /** 按 id 找供应商（导出写编码、名称），不看状态 */
    List<SupplierBrief> selectSuppliersByIds(@Param("ids") Collection<Long> ids);

    /**
     * 锁住这个物料到事务结束，返回 id；不存在返回 null。加 SKU、删 SKU 前先锁：
     * 两个人同时删最后两个 SKU，后一个等前一个提交再数，不会把 SKU 删光
     */
    @Select("SELECT id FROM bas_material WHERE id = #{id} FOR UPDATE")
    Long lockById(@Param("id") long id);
}
