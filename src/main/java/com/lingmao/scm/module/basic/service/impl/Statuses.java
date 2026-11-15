package com.lingmao.scm.module.basic.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lingmao.scm.common.exception.BizException;

import java.util.function.Supplier;

/**
 * 主数据的启用、停用（1 启用 0 停用）：status 标了 NEVER，保存写不到它，只由这里的条件更新改（RFC 003 §2、RFC 001 §4.4）。
 * basic 的表都有 id、status 两列，所以按列名写，一个方法给所有表用
 */
final class Statuses {

    static final int ENABLED = 1;
    static final int DISABLED = 0;

    private Statuses() {
    }

    /**
     * 把 id 这一行的 status 改成 value。传一个空实体（empty.get()）：有实体，修改人、修改时间才会自动填上。
     * 0 行说明这一行不存在（或者刚被删了），抛 404
     */
    static <T> void set(BaseMapper<T> mapper, Supplier<T> empty, long id, int value, String what) {
        int rows = mapper.update(empty.get(), new UpdateWrapper<T>().set("status", value).eq("id", id));
        if (rows == 0) {
            throw new BizException(404, what + "不存在: " + id);
        }
    }

    static boolean enabled(Integer status) {
        return status != null && status == ENABLED;
    }
}
