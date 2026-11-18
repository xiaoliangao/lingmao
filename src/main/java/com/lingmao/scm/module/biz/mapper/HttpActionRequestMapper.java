package com.lingmao.scm.module.biz.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.UUID;

@Mapper
public interface HttpActionRequestMapper {
    record Scope(String tenant, long userId, Long companyId, String operation, UUID key) { }
    record Stored(String requestHash, String state, String responseJson) { }

    @Select("SELECT pg_try_advisory_xact_lock(#{key})")
    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    boolean tryLock(long key);

    @Select("SELECT request_hash, state, response_json::text AS response_json FROM biz_http_action "
            + "WHERE tenant_code = #{s.tenant} AND user_id = #{s.userId} "
            + "AND operation = #{s.operation} AND request_key = #{s.key}")
    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    Stored find(@Param("s") Scope scope);

    @Insert("INSERT INTO biz_http_action (tenant_code,user_id,company_id,operation,request_key,request_hash,state) "
            + "VALUES (#{s.tenant},#{s.userId},#{s.companyId},#{s.operation},#{s.key},#{hash},'PROCESSING')")
    int claim(@Param("s") Scope scope, @Param("hash") String hash);

    @Update("UPDATE biz_http_action SET state='COMPLETED', response_json=CAST(#{json} AS jsonb), completed_at=clock_timestamp() "
            + "WHERE tenant_code=#{s.tenant} AND user_id=#{s.userId} "
            + "AND operation=#{s.operation} AND request_key=#{s.key} AND state='PROCESSING'")
    int complete(@Param("s") Scope scope, @Param("json") String json);
}
