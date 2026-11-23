package com.lingmao.scm.module.biz.service;

import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.common.exception.ErrorCodes;
import com.lingmao.scm.common.scope.CompanyContext;
import com.lingmao.scm.module.biz.entity.TagObjectType;
import com.lingmao.scm.security.LoginUser;

import java.util.Objects;

/** 各域拥有对象授权和真实父头锁；标签域只依赖此 SPI，不向上查业务表。 */
public interface TagObjectProvider {
    enum Access {
        READ, WRITE, REPLAY;
        public boolean writes() { return this != READ; }
        public boolean locks() { return this == WRITE; }
    }

    record Target(TagObjectType type, long id, Long companyId) {
        public Target {
            if (type == null || id <= 0 || companyId != null && companyId <= 0) {
                throw new BizException(400, "标签对象参数不合法");
            }
        }
    }

    TagObjectType type();

    /** WRITE 须在调用方事务内 FOR UPDATE 锁父头；REPLAY 重验当前编辑权限和范围，不重跑成功后的状态变更。 */
    Target resolve(long id, Access access);

    static void permission(Access access, String read, String write) {
        if (LoginUser.currentUserId() == null) throw new BizException(401, "请先登录");
        if (!LoginUser.hasAuthority(access.writes() ? write : read)) {
            throw new BizException(403, "没有操作此对象标签的权限");
        }
    }

    static <T> T present(T row) {
        if (row == null) throw new BizException(404, "对象不存在或无权访问");
        return row;
    }

    static Target target(TagObjectType type, long id, Long companyId) {
        // NULL 表示父头真实没有主体；共享资料不能被请求头伪造成某公司私有资料。
        if (companyId != null && CompanyContext.isHttpRequest()
                && !Objects.equals(CompanyContext.header(true), companyId)) {
            throw new BizException(403, "当前页面主体与目标对象不一致，请重新打开对象");
        }
        return new Target(type, id, companyId);
    }

    static void editable(boolean allowed) {
        if (!allowed) throw new BizException(ErrorCodes.STATE_CHANGED, "对象当前状态不允许修改标签，请刷新后重试");
    }
}
