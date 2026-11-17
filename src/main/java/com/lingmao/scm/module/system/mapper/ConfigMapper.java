package com.lingmao.scm.module.system.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** sys_config：一个键一行，值是 JSONB。没有实体：只按键取、按键存，读写都是 JSON 文本 */
@Mapper
public interface ConfigMapper {

    @Select("SELECT config_value::text FROM sys_config WHERE config_key = #{key}")
    String selectValue(@Param("key") String key);

    @Insert("""
            INSERT INTO sys_config (config_key, config_value, update_by, update_time)
            VALUES (#{key}, CAST(#{json} AS jsonb), #{userId}, now())
            ON CONFLICT (config_key) DO UPDATE
            SET config_value = EXCLUDED.config_value, update_by = EXCLUDED.update_by, update_time = now()
            """)
    void upsert(@Param("key") String key, @Param("json") String json, @Param("userId") Long userId);
}
