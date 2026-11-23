package com.lingmao.scm.module.fg.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.lingmao.scm.module.fg.dto.FgStockQuery;
import com.lingmao.scm.module.fg.dto.FgStockRowVO;
import com.lingmao.scm.module.fg.entity.FgStock;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Collection;
import java.util.List;

/**
 * 成品库存的两条条件更新（架构 §5.2，照物料的 StockMapper）只给 FgLedger 用。写成 @Select 拿 RETURNING 回来的结存；
 * flushCache 让 MyBatis 每次都真的去执行
 */
@Mapper
public interface FgStockMapper extends BaseMapper<FgStock> {

    /** 加库存：没有这一行就建，有就加。返回加完的结存 */
    @Select("INSERT INTO fg_stock (company_id, warehouse_id, style_sku_id, location_id, qty, movement_revision) VALUES (#{companyId}, #{warehouseId}, #{skuId}, #{locationId}, #{delta}, 1) "
            + "ON CONFLICT (company_id, warehouse_id, style_sku_id, location_id) "
            + "DO UPDATE SET qty = fg_stock.qty + EXCLUDED.qty, movement_revision = fg_stock.movement_revision + 1, update_time = now() WHERE fg_stock.qty::bigint + EXCLUDED.qty <= 2147483647 RETURNING qty")
    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    Integer add(@Param("companyId") long companyId, @Param("warehouseId") long warehouseId,
                @Param("skuId") long skuId, @Param("locationId") long locationId, @Param("delta") int delta);

    /** 减库存（delta 是负数）：减完不能是负的，不够就不改、返回 null。没有这一行也是 null */
    @Select("UPDATE fg_stock SET qty = qty + #{delta}, movement_revision = movement_revision + 1, update_time = now() "
            + "WHERE company_id = #{companyId} AND warehouse_id = #{warehouseId} AND style_sku_id = #{skuId} AND location_id = #{locationId} AND qty::bigint + #{delta} >= 0 RETURNING qty")
    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    Integer deduct(@Param("companyId") long companyId, @Param("warehouseId") long warehouseId,
                   @Param("skuId") long skuId, @Param("locationId") long locationId, @Param("delta") int delta);

    /** 一个仓几个 SKU 的库存行；没有库存记录的不在结果里 */
    @Select("<script>SELECT * FROM fg_stock WHERE company_id = #{companyId} AND warehouse_id = #{warehouseId} AND location_id = 0 AND style_sku_id IN "
            + "<foreach collection='skuIds' item='id' open='(' separator=',' close=')'>#{id}</foreach></script>")
    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    List<FgStock> selectBySkus(@Param("companyId") long companyId, @Param("warehouseId") long warehouseId,
                               @Param("skuIds") Collection<Long> skuIds);

    @Select("SELECT qty FROM fg_stock WHERE company_id = #{companyId} AND warehouse_id = #{warehouseId} "
            + "AND style_sku_id = #{skuId} AND location_id = #{locationId}")
    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    Integer current(@Param("companyId") long companyId, @Param("warehouseId") long warehouseId,
                    @Param("skuId") long skuId, @Param("locationId") long locationId);

    /** 库存明细（XML）：仓库名只读 JOIN；SKU 的显示字段由 Service 补 */
    IPage<FgStockRowVO> selectRowPage(IPage<FgStockRowVO> page, @Param("q") FgStockQuery query, @Param("codes") List<String> codes,
                                         @Param("companyId") long companyId);

    /** 按款：一页款式 id（有库存记录的款，按款号） */
    IPage<Long> selectStylePage(IPage<Long> page, @Param("q") FgStockQuery query, @Param("codes") List<String> codes,
                                         @Param("companyId") long companyId);

    /** 已预算内矩阵 SKU 的库存合计；不得按款式再次展开并发新建的色码。 */
    record SkuQty(Long styleSkuId, long qty) {
    }

    @Select("<script>SELECT s.style_sku_id, SUM(s.qty) FROM fg_stock s "
            + "WHERE s.company_id = #{companyId} AND "
            + "<choose><when test='skuIds != null and !skuIds.isEmpty()'>s.style_sku_id IN "
            + "<foreach collection='skuIds' item='id' open='(' separator=',' close=')'>#{id}</foreach>"
            + "</when><otherwise>FALSE</otherwise></choose> "
            + "<if test='warehouseId != null'>AND s.warehouse_id = #{warehouseId}</if> "
            + "GROUP BY s.style_sku_id ORDER BY s.style_sku_id</script>")
    List<SkuQty> sumBySkus(@Param("skuIds") Collection<Long> skuIds, @Param("warehouseId") Long warehouseId,
                             @Param("companyId") long companyId);
}
