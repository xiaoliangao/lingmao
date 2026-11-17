package com.lingmao.scm.module.inventory.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lingmao.scm.module.inventory.entity.InvCountItem;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface InvCountItemMapper extends BaseMapper<InvCountItem> {

    @Select("SELECT * FROM inv_count_item WHERE count_id = #{countId} ORDER BY seq, id")
    List<InvCountItem> selectByCount(@Param("countId") long countId);
}
