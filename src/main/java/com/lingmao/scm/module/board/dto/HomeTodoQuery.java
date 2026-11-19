package com.lingmao.scm.module.board.dto;

import lombok.Data;

/**
 * 待办的筛选（docs/api/06 §2.3）：只对数据范围是"全部"的人有用，别人传了也只看负责人是自己的。
 * "待我审核"一组和审核类的数不受它影响
 */
@Data
public class HomeTodoQuery {
    /** true：只看负责人是我的 */
    private boolean mine;
    /** 只看负责人是这个人的（生产跟单、采购员……），和 mine 二选一 */
    private Long ownerId;
}
