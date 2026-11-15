package com.lingmao.scm.module.basic.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lingmao.scm.module.basic.entity.Company;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface CompanyMapper extends BaseMapper<Company> {

    /**
     * 锁住全部启用的主体，持有到事务结束，返回它们的 id（RFC 003 §3.1）。停用、删除前先锁再数：
     * 两个人同时停用最后两个，后一个等前一个提交，再查时前一个已经不算启用了，只剩一个，被拦住
     */
    @Select("SELECT id FROM bas_company WHERE status = 1 FOR UPDATE")
    List<Long> lockEnabledIds();
}
