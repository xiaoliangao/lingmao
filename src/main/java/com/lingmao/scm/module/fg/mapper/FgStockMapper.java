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
    @Select("INSERT INTO fg_stock (warehouse_id, style_sku_id, location_id, qty) VALUES (#{warehouseId}, #{skuId}, 0, #{delta}) "
            + "ON CONFLICT (warehouse_id, style_sku_id, location_id) "
            + "DO UPDATE SET qty = fg_stock.qty + EXCLUDED.qty, update_time = now() RETURNING qty")
    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    Integer add(@Param("warehouseId") long warehouseId, @Param("skuId") long skuId, @Param("delta") int delta);

    /** 减库存（delta 是负数）：减完不能是负的，不够就不改、返回 null。没有这一行也是 null */
    @Select("UPDATE fg_stock SET qty = qty + #{delta}, update_time = now() "
            + "WHERE warehouse_id = #{warehouseId} AND style_sku_id = #{skuId} AND location_id = 0 AND qty + #{delta} >= 0 RETURNING qty")
    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    Integer deduct(@Param("warehouseId") long warehouseId, @Param("skuId") long skuId, @Param("delta") int delta);

    /** 一个仓几个 SKU 的库存行；没有库存记录的不在结果里 */
    @Select("<script>SELECT * FROM fg_stock WHERE warehouse_id = #{warehouseId} AND location_id = 0 AND style_sku_id IN "
            + "<foreach collection='skuIds' item='id' open='(' separator=',' close=')'>#{id}</foreach></script>")
    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    List<FgStock> selectBySkus(@Param("warehouseId") long warehouseId, @Param("skuIds") Collection<Long> skuIds);

    /** 库存明细（XML）：仓库名只读 JOIN；SKU 的显示字段由 Service 补 */
    IPage<FgStockRowVO> selectRowPage(IPage<FgStockRowVO> page, @Param("q") FgStockQuery query, @Param("codes") List<String> codes);

    /** 按款：一页款式 id（有库存记录的款，按款号） */
    IPage<Long> selectStylePage(IPage<Long> page, @Param("q") FgStockQuery query, @Param("codes") List<String> codes);

    /** 几个款的库存按 SKU 合计（几个仓加在一起，或只看一个仓） */
    record SkuQty(Long styleSkuId, int qty) {
    }

    @Select("<script>SELECT s.style_sku_id, SUM(s.qty) FROM fg_stock s JOIN sty_style_sku k ON k.id = s.style_sku_id "
            + "WHERE k.style_id IN <foreach collection='styleIds' item='id' open='(' separator=',' close=')'>#{id}</foreach> "
            + "<if test='warehouseId != null'>AND s.warehouse_id = #{warehouseId}</if> GROUP BY s.style_sku_id</script>")
    List<SkuQty> sumByStyles(@Param("styleIds") Collection<Long> styleIds, @Param("warehouseId") Long warehouseId);
}
