package com.lingmao.scm.module.techpack.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lingmao.scm.module.techpack.entity.ProcessImage;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ProcessImageMapper extends BaseMapper<ProcessImage> {

    @Delete("DELETE FROM tp_process_image WHERE process_id IN (SELECT id FROM tp_process WHERE tech_pack_id = #{packId})")
    int deleteByPack(@Param("packId") long packId);
}
