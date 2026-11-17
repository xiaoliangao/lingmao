package com.lingmao.scm.module.inventory.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.lingmao.scm.module.inventory.dto.StockQuery;
import com.lingmao.scm.module.inventory.dto.StockRowVO;
import com.lingmao.scm.module.inventory.dto.StockSummaryRowVO;
import com.lingmao.scm.module.inventory.entity.Stock;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;

/**
 * 库存的两条条件更新（架构 §5.2）只给 StockLedger 用。写成 @Select 是为了拿 RETURNING 回来的结存；
 * flushCache 让 MyBatis 每次都真的去执行，不拿一级缓存里上一次的结果
 */
@Mapper
public interface StockMapper extends BaseMapper<Stock> {

    /** 加库存：没有这一行就建，有就加。返回加完的结存 */
    @Select("INSERT INTO inv_stock (warehouse_id, material_sku_id, owner_type, owner_id, location_id, qty) "
            + "VALUES (#{warehouseId}, #{skuId}, #{ownerType}, #{ownerId}, #{locationId}, #{delta}) "
            + "ON CONFLICT (warehouse_id, material_sku_id, owner_type, owner_id, location_id) "
            + "DO UPDATE SET qty = inv_stock.qty + EXCLUDED.qty, update_time = now() RETURNING qty")
    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    BigDecimal add(@Param("warehouseId") long warehouseId, @Param("skuId") long skuId, @Param("ownerType") String ownerType,
                   @Param("ownerId") long ownerId, @Param("locationId") long locationId, @Param("delta") BigDecimal delta);

    /** 减库存（delta 是负数）：减完不能是负的，不够就不改、返回 null。没有这一行也是 null */
    @Select("UPDATE inv_stock SET qty = qty + #{delta}, update_time = now() "
            + "WHERE warehouse_id = #{warehouseId} AND material_sku_id = #{skuId} AND owner_type = #{ownerType} "
            + "AND owner_id = #{ownerId} AND location_id = #{locationId} AND qty + #{delta} >= 0 RETURNING qty")
    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    BigDecimal deduct(@Param("warehouseId") long warehouseId, @Param("skuId") long skuId, @Param("ownerType") String ownerType,
                      @Param("ownerId") long ownerId, @Param("locationId") long locationId, @Param("delta") BigDecimal delta);

    /** 一个仓几个 SKU 的库存行（自有、不分货位）；没有库存记录的不在结果里 */
    @Select("<script>SELECT * FROM inv_stock WHERE warehouse_id = #{warehouseId} AND owner_type = 'OWN' AND owner_id = 0 "
            + "AND location_id = 0 AND material_sku_id IN "
            + "<foreach collection='skuIds' item='id' open='(' separator=',' close=')'>#{id}</foreach></script>")
    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    List<Stock> selectBySkus(@Param("warehouseId") long warehouseId, @Param("skuIds") Collection<Long> skuIds);

    /** 一个仓有库存记录的 SKU（盘点"全部"、"按分类"带出明细）；categoryIds 是 null = 不按分类筛 */
    @Select("<script>SELECT s.* FROM inv_stock s JOIN bas_material_sku k ON k.id = s.material_sku_id "
            + "JOIN bas_material m ON m.id = k.material_id "
            + "WHERE s.warehouse_id = #{warehouseId} AND s.owner_type = 'OWN' AND s.owner_id = 0 AND s.location_id = 0 "
            + "<if test='categoryIds != null'>AND m.category_id IN "
            + "<foreach collection='categoryIds' item='id' open='(' separator=',' close=')'>#{id}</foreach></if> "
            + "ORDER BY k.code, s.id</script>")
    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    List<Stock> selectForCount(@Param("warehouseId") long warehouseId, @Param("categoryIds") Collection<Long> categoryIds);

    /** 库存明细（XML） */
    IPage<StockRowVO> selectRowPage(IPage<StockRowVO> page, @Param("q") StockQuery query, @Param("codes") List<String> codes);


    /** 按物料汇总（XML） */
    IPage<StockSummaryRowVO> selectSummaryPage(IPage<StockSummaryRowVO> page, @Param("q") StockQuery query,
                                               @Param("codes") List<String> codes);

}
