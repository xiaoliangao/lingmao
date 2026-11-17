package com.lingmao.scm.module.techpack.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lingmao.scm.module.techpack.entity.BomItemSku;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Collection;
import java.util.List;

@Mapper
public interface BomItemSkuMapper extends BaseMapper<BomItemSku> {

    /** 一个版本全部 BOM 行的配料（整版覆盖、删草稿时） */
    @Delete("DELETE FROM tp_bom_item_sku WHERE bom_item_id IN (SELECT id FROM tp_bom_item WHERE tech_pack_id = #{packId})")
    int deleteByPack(@Param("packId") long packId);

    /** 按款色配料的一行（随款色、随款色和尺码）配了的一个款色；这一行一条配料都没有时 colorId 是 null */
    record ColorRow(Long techPackId, Long bomItemId, Long colorId) {
    }

    /** 几个版本里按款色配料的行各配了哪些款色（PackBrief.colorIds）；没有按款色配料的行的版本不出现 */
    @Select("<script>SELECT DISTINCT b.tech_pack_id, b.id AS bom_item_id, s.color_id FROM tp_bom_item b "
            + "LEFT JOIN tp_bom_item_sku s ON s.bom_item_id = b.id "
            + "WHERE b.sku_mode IN ('BY_COLOR', 'BY_COLOR_SIZE') AND b.tech_pack_id IN "
            + "<foreach collection='packIds' item='id' open='(' separator=',' close=')'>#{id}</foreach></script>")
    List<ColorRow> selectColorRows(@Param("packIds") Collection<Long> packIds);
}
