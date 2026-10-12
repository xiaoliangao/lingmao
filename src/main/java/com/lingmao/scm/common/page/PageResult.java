package com.lingmao.scm.common.page;

import com.baomidou.mybatisplus.core.metadata.IPage;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

/**
 * 分页返回：{list, total}。前端表格要的就是这两样，不把 MyBatis-Plus 的 Page 对象直接暴露出去。
 */
@Data
@AllArgsConstructor
public class PageResult<T> {
    private List<T> list;
    private long total;

    public static <T> PageResult<T> of(IPage<T> page) {
        return new PageResult<>(page.getRecords(), page.getTotal());
    }
}
