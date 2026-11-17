package com.lingmao.scm.module.techpack.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lingmao.scm.module.techpack.entity.BomItemSize;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface BomItemSizeMapper extends BaseMapper<BomItemSize> {

    @Delete("DELETE FROM tp_bom_item_size WHERE bom_item_id IN (SELECT id FROM tp_bom_item WHERE tech_pack_id = #{packId})")
    int deleteByPack(@Param("packId") long packId);
}
