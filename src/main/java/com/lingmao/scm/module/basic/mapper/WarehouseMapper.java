package com.lingmao.scm.module.basic.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.lingmao.scm.module.basic.dto.WarehouseQuery;
import com.lingmao.scm.module.basic.dto.WarehouseVO;
import com.lingmao.scm.module.basic.entity.Warehouse;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 列表的 SQL 在 resources/mapper/basic/WarehouseMapper.xml：JOIN sup_supplier 只取工厂简称（架构 §3.2 规则 4） */
@Mapper
public interface WarehouseMapper extends BaseMapper<Warehouse> {

    IPage<WarehouseVO> selectVOPage(IPage<WarehouseVO> page, @Param("q") WarehouseQuery query);

    WarehouseVO selectVOById(@Param("id") long id);
}
