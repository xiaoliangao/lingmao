package com.lingmao.scm.module.basic.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lingmao.scm.module.basic.entity.MaterialSku;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface MaterialSkuMapper extends BaseMapper<MaterialSku> {
    @org.apache.ibatis.annotations.Select("""
            <script>SELECT count(*) FROM (SELECT 1 FROM bas_material_sku WHERE material_id IN
            <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
            LIMIT #{limit}) bounded</script>
            """)
    int countExportRows(@org.apache.ibatis.annotations.Param("ids") java.util.List<Long> ids,
                        @org.apache.ibatis.annotations.Param("limit") int limit);

    /** 只取排序键；SKU 实体仍由生产者每 500 个 ID 读取。 */
    record ExportOrder(long id, long materialId, String code) { }

    @org.apache.ibatis.annotations.Options(useCache = false, flushCache = org.apache.ibatis.annotations.Options.FlushCachePolicy.TRUE)
    @org.apache.ibatis.annotations.Select("""
            <script>SELECT id,material_id,code FROM bas_material_sku
            WHERE material_id IN <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
            <if test="afterId != null">AND id &gt; #{afterId}</if>
            ORDER BY id LIMIT 500</script>
            """)
    java.util.List<ExportOrder> selectExportOrderPage(@org.apache.ibatis.annotations.Param("ids") java.util.List<Long> ids,
                                                     @org.apache.ibatis.annotations.Param("afterId") Long afterId);

}
