package com.lingmao.scm.common.page;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

/**
 * 分页查询的公共入参：所有列表接口的 Query 对象都继承它，前端统一传 page 和 size。
 * 为什么限制 size 上限：防止一次拉几十万行把数据库和内存打爆。
 */
@Data
public class PageQuery {

    @Min(value = 1, message = "page 从 1 开始")
    private long page = 1;

    @Min(value = 1, message = "size 至少 1")
    @Max(value = 500, message = "size 最多 500")
    private long size = 20;

    /** 转成 MyBatis-Plus 的分页对象 */
    public <T> Page<T> toPage() {
        return new Page<>(page, size);
    }
}
