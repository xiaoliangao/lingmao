package com.lingmao.scm.module.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lingmao.scm.module.system.entity.SysCodeRule;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface SysCodeRuleMapper extends BaseMapper<SysCodeRule> {

    /**
     * 存规则之前锁住这种编码的通用规则那一行，同一种编码的保存排队（RFC 018 §4.8）：
     * "一个大类只能在一条规则里"由 Service 查，两个人同时把 FS 放进两条规则，各自查都没撞上
     */
    @Select("SELECT id FROM sys_code_rule WHERE target = #{target} AND scope_values = '{}' FOR UPDATE")
    Long lockGeneral(@Param("target") String target);
}
