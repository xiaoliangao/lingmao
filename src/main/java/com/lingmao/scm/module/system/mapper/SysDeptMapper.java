package com.lingmao.scm.module.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lingmao.scm.module.system.entity.SysDept;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface SysDeptMapper extends BaseMapper<SysDept> {

    /**
     * 改部门树之前锁整张表，锁到事务结束：两个人同时挪部门，各自查都不成环、一起提交就成了环（DeptServiceImpl）。
     * SHARE ROW EXCLUSIVE 和自己互斥，不挡读
     */
    @Update("LOCK TABLE sys_dept IN SHARE ROW EXCLUSIVE MODE")
    void lockTable();
}
