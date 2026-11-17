package com.lingmao.scm.module.techpack.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lingmao.scm.module.techpack.entity.SpecValue;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface SpecValueMapper extends BaseMapper<SpecValue> {

    @Delete("DELETE FROM tp_spec_value WHERE spec_point_id IN (SELECT id FROM tp_spec_point WHERE tech_pack_id = #{packId})")
    int deleteByPack(@Param("packId") long packId);
}
