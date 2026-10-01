package com.lingmao.scm.module.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lingmao.scm.module.system.entity.DictType;
import org.apache.ibatis.annotations.Mapper;

/**
 * Mapper = 数据访问层，只跟表打交道。
 * 继承 BaseMapper 就免费获得 insert / selectById / selectPage / updateById / deleteById 等单表方法。
 * 复杂 SQL（多表联查、报表）才在 resources/mapper/*.xml 里手写，方法声明在这里。
 */
@Mapper
public interface DictTypeMapper extends BaseMapper<DictType> {
}
