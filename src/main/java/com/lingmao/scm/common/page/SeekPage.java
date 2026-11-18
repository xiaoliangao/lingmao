package com.lingmao.scm.common.page;

import java.util.List;

/** 流水页不隐式 COUNT；nextCursor 仅在还有下一页时返回。 */
public record SeekPage<T>(List<T> list, String nextCursor, boolean hasMore) {
    public SeekPage {
        list = List.copyOf(list);
    }
}
