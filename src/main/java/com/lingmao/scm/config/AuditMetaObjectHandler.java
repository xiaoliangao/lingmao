package com.lingmao.scm.config;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;

/**
 * 审计字段自动填充：MyBatis-Plus 在每次 insert / update 前回调这里。
 * 为什么：不然每个 Service 都要手写 setCreateTime(now)，总有人忘。
 * createBy / updateBy 等阶段 2 有登录用户后再填，现在先留空。
 */
@Component
public class AuditMetaObjectHandler implements MetaObjectHandler {

    @Override
    public void insertFill(MetaObject metaObject) {
        OffsetDateTime now = OffsetDateTime.now();
        strictInsertFill(metaObject, "createTime", OffsetDateTime.class, now);
        strictInsertFill(metaObject, "updateTime", OffsetDateTime.class, now);
    }

    @Override
    public void updateFill(MetaObject metaObject) {
        strictUpdateFill(metaObject, "updateTime", OffsetDateTime.class, OffsetDateTime.now());
    }
}
