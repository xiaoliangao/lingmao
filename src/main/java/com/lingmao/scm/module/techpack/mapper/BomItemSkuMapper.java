package com.lingmao.scm.module.techpack.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lingmao.scm.module.techpack.entity.BomItemSku;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface BomItemSkuMapper extends BaseMapper<BomItemSku> {

    /** 一个版本全部 BOM 行的配料（整版覆盖、删草稿时） */
    @Delete("DELETE FROM tp_bom_item_sku WHERE bom_item_id IN (SELECT id FROM tp_bom_item WHERE tech_pack_id = #{packId})")
    int deleteByPack(@Param("packId") long packId);
}
