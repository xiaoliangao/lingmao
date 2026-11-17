package com.lingmao.scm.module.purchase.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lingmao.scm.module.purchase.entity.MrpCalcItem;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;

@Mapper
public interface MrpCalcItemMapper extends BaseMapper<MrpCalcItem> {

    @Select("SELECT * FROM mrp_calc_item WHERE calc_id = #{calcId} ORDER BY seq, id")
    List<MrpCalcItem> selectByCalc(@Param("calcId") long calcId);

    @Select("<script>SELECT * FROM mrp_calc_item WHERE calc_id IN "
            + "<foreach collection='calcIds' item='id' open='(' separator=',' close=')'>#{id}</foreach> ORDER BY calc_id, seq, id</script>")
    List<MrpCalcItem> selectByCalcs(@Param("calcIds") Collection<Long> calcIds);

    /**
     * 已转采购 = 指着它的、采购单没作废的采购行数量合计（采购单位）。生成、改数量、删、作废、重新算料转行以后调，
     * 调之前先锁住算料单（MrpCalcMapper.lockByIds），两个事务同时改才不会互相覆盖
     */
    @Update("<script>UPDATE mrp_calc_item ci SET converted_qty = COALESCE((SELECT SUM(i.qty) FROM pur_order_item i "
            + "JOIN pur_order o ON o.id = i.purchase_order_id WHERE i.calc_item_id = ci.id AND o.status &lt;&gt; 'VOIDED'), 0) "
            + "WHERE ci.id IN <foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach></script>")
    void syncConverted(@Param("ids") Collection<Long> ids);

    /** 已领（生产领料回写），调之前先锁住算料单 */
    @Update("UPDATE mrp_calc_item SET issued_qty = #{qty} WHERE id = #{id}")
    void updateIssued(@Param("id") long id, @Param("qty") BigDecimal qty);

    @Delete("DELETE FROM mrp_calc_item WHERE calc_id = #{calcId}")
    void deleteByCalc(@Param("calcId") long calcId);
}
