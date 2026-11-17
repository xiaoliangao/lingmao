package com.lingmao.scm.module.inventory.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lingmao.scm.module.inventory.entity.InvCountCategory;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface InvCountCategoryMapper extends BaseMapper<InvCountCategory> {

    @Select("SELECT category_id FROM inv_count_category WHERE count_id = #{countId}")
    List<Long> categoryIds(@Param("countId") long countId);

    @Delete("DELETE FROM inv_count_category WHERE count_id = #{countId}")
    int deleteByCount(@Param("countId") long countId);
}
