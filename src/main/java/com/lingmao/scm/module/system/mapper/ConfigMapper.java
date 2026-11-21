package com.lingmao.scm.module.system.mapper;

import lombok.Data;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.OffsetDateTime;

/** sys_config：一个键一行，值是 JSONB。没有实体：只按键取、按键存，读写都是 JSON 文本 */
@Mapper
public interface ConfigMapper {

    @Select("SELECT config_value::text FROM sys_config WHERE config_key = #{key}")
    String selectValue(@Param("key") String key);

    /** 连同谁、什么时候改的一起取（设置页显示"上次谁改的"）；没有这一行是 null */
    @Select("SELECT config_value::text AS config_value, update_by, update_time FROM sys_config WHERE config_key = #{key}")
    Row selectRow(@Param("key") String key);

    /** 先读后写的改值用：没有这一行就插一个空的（有就不动），之后 selectValueForUpdate 才锁得住它 */
    @Insert("INSERT INTO sys_config (config_key, config_value) VALUES (#{key}, '{}'::jsonb) ON CONFLICT (config_key) DO NOTHING")
    void ensure(@Param("key") String key);

    /** 锁住这一行再读（事务里用）：两个人同时改，后来的等前一个提交、读到它改完的值 */
    @Select("SELECT config_value::text FROM sys_config WHERE config_key = #{key} FOR UPDATE")
    String selectValueForUpdate(@Param("key") String key);

    @Insert("""
            INSERT INTO sys_config (config_key, config_value, update_by, update_time)
            VALUES (#{key}, CAST(#{json} AS jsonb), #{userId}, now())
            ON CONFLICT (config_key) DO UPDATE
            SET config_value = EXCLUDED.config_value, update_by = EXCLUDED.update_by, update_time = now()
            """)
    void upsert(@Param("key") String key, @Param("json") String json, @Param("userId") Long userId);

    /** selectRow 的一行：值是 JSON 文本 */
    @Data
    class Row {
        private String configValue;
        private Long updateBy;
        private OffsetDateTime updateTime;
    }
}
