package com.lingmao.scm.module.system.mapper;


import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lingmao.scm.module.system.entity.DictData;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface DictDataMapper extends BaseMapper<DictData> {
    /**
     * 应用启动时，MyBatis 会自动生成一个实现这个接口的对象（叫"代理"），放进 Spring 容器。Service 里注入的就是它。
     * 尖括号里的 DictData 告诉框架"我管这张表"，于是 insert、selectById、selectPage、deleteById、exists 这些方法都是现成的。
     * 给扫描器做个标记，告诉它"这个接口要生成实现"。
     */
}
