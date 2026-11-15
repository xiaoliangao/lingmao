package com.lingmao.scm.config;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.lingmao.scm.security.LoginUser;
import lombok.RequiredArgsConstructor;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.OffsetDateTime;

/**
 * 审计字段自动填充：MyBatis-Plus 在每次 insert / update 前回调这里（RFC 001 §4.1）。
 * 为什么：不然每个 Service 都要手写 setCreateTime(now)、setUpdateBy(当前用户)，总有人忘。
 * 人从登录信息里取（LoginUser.currentUserId），没有登录用户的场合（启动时建 admin、以后的定时任务）留空；
 * 时间从注入的 Clock 取，测试里换成固定时钟就能断言。
 * <p>
 * 只有实体参与的更新才会回调这里：updateById(实体)、update(实体, 条件)。update(null, 条件) 没有实体，不填，
 * 所以"由动作改"的条件更新要写成 update(new Xxx(), 条件)，update_time、update_by 才会跟着变。
 */
@Component
@RequiredArgsConstructor
public class AuditMetaObjectHandler implements MetaObjectHandler {

    private final Clock clock;

    /** 插入：四列都填。strictInsertFill 只在字段还是 null 时填，代码里显式给了值的不覆盖 */
    @Override
    public void insertFill(MetaObject metaObject) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        Long userId = LoginUser.currentUserId();
        strictInsertFill(metaObject, "createTime", OffsetDateTime.class, now);
        strictInsertFill(metaObject, "updateTime", OffsetDateTime.class, now);
        strictInsertFill(metaObject, "createBy", Long.class, userId);
        strictInsertFill(metaObject, "updateBy", Long.class, userId);
    }

    /**
     * 更新：无条件覆盖。修改是先查出实体（update_time、update_by 都有旧值）再写回去的，
     * 用 strictUpdateFill 的话"有值就不填"，旧值原样写回，修改时间就一直不变（bug 002）。
     * update_by 没有登录用户时也要覆盖成 null：系统改的就是系统改的，不能记成上一个改它的人。
     * 这两列带 fill = INSERT_UPDATE，MyBatis-Plus 生成的 SET 里无条件有它们（不看是不是 null），所以 null 也能写进库
     */
    @Override
    public void updateFill(MetaObject metaObject) {
        if (metaObject.hasSetter("updateTime")) {
            metaObject.setValue("updateTime", OffsetDateTime.now(clock));
        }
        if (metaObject.hasSetter("updateBy")) {
            metaObject.setValue("updateBy", LoginUser.currentUserId());
        }
    }
}
