package com.lingmao.scm.module.biz.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.UUID;

@Mapper
public interface ActionRequestMapper {
    record Claim(long id, String requestHash, String result) { }

    @Select("SELECT pg_try_advisory_xact_lock(#{key})")
    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    boolean tryLock(long key);

    @Insert("INSERT INTO biz_action_request (user_id, operation, client_action_id, request_hash) "
            + "VALUES (#{userId}, #{operation}, #{actionId}, #{hash}) "
            + "ON CONFLICT (user_id, operation, client_action_id) DO NOTHING")
    int claim(@Param("userId") long userId, @Param("operation") String operation, @Param("actionId") UUID actionId,
              @Param("hash") String hash);

    @Select("SELECT id, request_hash, result::text AS result FROM biz_action_request "
            + "WHERE user_id = #{userId} AND operation = #{operation} AND client_action_id = #{actionId} FOR UPDATE")
    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    Claim lock(@Param("userId") long userId, @Param("operation") String operation, @Param("actionId") UUID actionId);

    /** 这个人的这次动作已经做完：认领和业务同一事务，result 有值就是成功了 */
    @Select("SELECT EXISTS (SELECT 1 FROM biz_action_request WHERE user_id = #{userId} "
            + "AND client_action_id = #{actionId} AND result IS NOT NULL)")
    boolean completed(@Param("userId") long userId, @Param("actionId") UUID actionId);

    @Update("UPDATE biz_action_request SET result = CAST(#{result} AS jsonb) WHERE id = #{id} AND result IS NULL")
    int complete(@Param("id") long id, @Param("result") String result);
}
